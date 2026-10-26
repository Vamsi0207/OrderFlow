"""Isolated, journaled Buy Now harness. Importing this module performs no I/O."""
import argparse
import concurrent.futures
import contextlib
import copy
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import stat
import subprocess
import sys
import threading
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RUNS = Path('/workspace/.orderflow/buy-now-runs')
CACHE = Path('/workspace/.orderflow/phase5-artifacts')
JAVA = Path('/workspace/.orderflow/jdk/bin')
DOCKER_CONFIG = Path('/workspace/.orderflow/phase5-docker-client')
DOCKER = ['docker', '--host', 'unix:///var/run/docker.sock', '--config', str(DOCKER_CONFIG)]
SERVICES = {'gateway':19080,'auth':19081,'product':19082,'order':19083,'inventory':19084,'payment':19085,'discovery':19761}
MODULES = {'gateway':'gateway','discovery':'discovery-server',**{k:k+'-service' for k in ('auth','product','order','inventory','payment')}}
IMAGES = {'java':'eclipse-temurin:21-jdk','postgres':'postgres:17','redis':'redis:7-alpine','kafka':'apache/kafka:4.1.0'}
DELETE_ORDER = [('order_notifications','order_id'),('legacy_payment_effects','order_id'),('checkout_attempts','order_id'),('payments','order_id'),('stock_reservation_items','order_id'),('stock_reservations','order_id'),('order_items','order_id')]
SECRET_NAMES = {'password','token','authorization','clientsecret','client_secret','secret','apikey','api_key'}
TERMINAL = {'cleaned'}

class SafetyError(Exception): pass
class Interrupted(SafetyError): pass

def require(condition, code):
    if not condition: raise SafetyError(code)

def uid(value):
    result=str(uuid.UUID(str(value)))
    require(result==str(value), 'NONCANONICAL_UUID')
    return result

def digest(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def encoded(value): return json.dumps(value,sort_keys=True,separators=(',',':')).encode()
def derived(seed, label): return hmac.new(seed.encode(),label.encode(),hashlib.sha256).hexdigest()
def environment(extra=None):
    # Never inherit Spring/JVM/proxy/Python customization or cloud credentials.
    value={'PATH':'/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin','LANG':'C.UTF-8','HOME':os.environ.get('HOME','/tmp')}
    value.update(extra or {})
    return value

def no_secrets(value):
    if isinstance(value,dict):
        for key,item in value.items():
            normalized=key.lower().replace('-','').replace('_','')
            sensitive=normalized in {k.replace('_','') for k in SECRET_NAMES}|{'jwtsecret','stripesecretkey','stripewebhooksecret','webhooksecret','runkey','seed','phase5runkey','phase5jwtkey','phase5stripekey','phase5webhookkey','dbpassword'}
            if sensitive:
                require(isinstance(item,str) and (item=='' or re.fullmatch(r'\$\{PHASE5_[A-Z_]+:\?\}',item)),'SECRET_FIELD_IN_MANIFEST')
            no_secrets(item)
    elif isinstance(value,list):
        for item in value:no_secrets(item)
    elif isinstance(value,str):
        require(not re.search(r'phase5_(?:password|mock|client)_[a-f0-9]{24,}|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+',value),'SECRET_VALUE_IN_MANIFEST')

def atomic(path, data):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    require(not path.is_symlink(),'SYMLINK_MANIFEST')
    temporary=path.with_name(path.name+'.new')
    fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_TRUNC|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'wb') as stream:stream.write(data);stream.flush();os.fsync(stream.fileno())
    os.replace(temporary,path)
    fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
    try:os.fsync(fd)
    finally:os.close(fd)

class Journal:
    def __init__(self, directory, data): self.directory=Path(directory);self.data=data;self.lock=threading.RLock()
    @classmethod
    def create(cls, root, run=None):
        run=uid(run or str(uuid.uuid4()));directory=Path(root)/run
        directory.mkdir(mode=0o700,parents=False,exist_ok=False)
        data={'version':1,'run':run,'project':'orderflow-buy-'+uuid.UUID(run).hex,'database':'of_'+uuid.UUID(run).hex,
              'status':'planned','last_step':'planned','operations':{},'resources':{},'containers':{},'outcome':'not_run'}
        value=cls(directory,data);value.save();return value
    @classmethod
    def load(cls, root, run):
        run=uid(run);directory=Path(root)/run
        require(not directory.is_symlink() and directory.resolve().parent==Path(root).resolve(),'UNSAFE_RUN_DIRECTORY')
        require(not Path(root).is_symlink() and directory.stat().st_uid==os.getuid() and stat.S_IMODE(directory.stat().st_mode)==0o700,'UNSAFE_RUN_DIRECTORY')
        file=directory/'manifest.json'
        require(not file.is_symlink() and file.stat().st_uid==os.getuid() and stat.S_IMODE(file.stat().st_mode)==0o600,'UNSAFE_MANIFEST_FILE')
        data=json.loads(file.read_text());require(data['run']==run and data['project']=='orderflow-buy-'+uuid.UUID(run).hex,'RUN_IDENTITY_MISMATCH')
        require(data['database']=='of_'+uuid.UUID(run).hex,'DATABASE_IDENTITY_MISMATCH');no_secrets(data)
        return cls(directory,data)
    def save(self):
        with self.lock:no_secrets(self.data);atomic(self.directory/'manifest.json',encoded(self.data))
    def step(self, step): self.data['last_step']=step;self.save()
    def intent(self, operation, **correlation):
        with self.lock:
            if operation not in self.data['operations']:
                self.data['operations'][operation]={'state':'pending','correlation':correlation};self.save()
            return self.data['operations'][operation]['correlation']
    def done(self,name):self.data['operations'][name]['state']='completed';self.step(name)
    def resource(self,kind,identifier):
        identifier=uid(identifier)
        with self.lock:
            values=self.data['resources'].setdefault(kind,[])
            if identifier not in values:values.append(identifier);self.save()
        return identifier

class Redactor:
    TOKEN=re.compile(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+')
    FIXTURE=re.compile(r'phase5_(?:password|mock|client)_[A-Za-z0-9_-]+')
    FIELD=re.compile(r'(?i)((?:password|authorization|client[_-]?secret|api[_-]?key|jwt[_-]?secret|webhook[_-]?secret)\s*[=:]\s*["\']?)[^\s,"\'}]+')
    def __init__(self):self.values=set();self.lock=threading.RLock()
    def add(self,value):
        if isinstance(value,str) and value:
            with self.lock:self.values.add(value)
    def text(self,value):
        with self.lock:
            for secret in sorted(self.values,key=len,reverse=True):value=value.replace(secret,'[REDACTED]')
        value=self.TOKEN.sub('[REDACTED]',value);value=self.FIXTURE.sub('[REDACTED]',value)
        return self.FIELD.sub(r'\1[REDACTED]',value)
    def stream(self,stream,path):
        # Buffer whole lines; discard oversized lines so a split secret is never persisted.
        fd=os.open(path,os.O_WRONLY|os.O_APPEND|os.O_CREAT|os.O_NOFOLLOW,0o600)
        pending=bytearray();discard=False
        with os.fdopen(fd,'a',encoding='utf-8') as output:
            while True:
                data=stream.read(512)
                if not data:break
                for byte in data:
                    if byte==10:
                        output.write('[OVERSIZED LINE SUPPRESSED]\n' if discard else self.text(pending.decode('utf-8','replace'))+'\n');output.flush();pending.clear();discard=False
                    elif not discard:
                        pending.append(byte)
                        if len(pending)>65536:pending.clear();discard=True
            if pending or discard:output.write('[OVERSIZED LINE SUPPRESSED]\n' if discard else self.text(pending.decode('utf-8','replace'))+'\n')

class StopFlag:
    def __init__(self):self.requested=False
    def handler(self,*args):self.requested=True  # Never interrupt the spawn/record critical section.
    def check(self):
        if self.requested:raise Interrupted('INTERRUPTED')
    @contextlib.contextmanager
    def installed(self):
        previous={s:signal.getsignal(s) for s in (signal.SIGINT,signal.SIGTERM)}
        for s in previous:signal.signal(s,self.handler)
        try:yield self
        finally:
            for s,h in previous.items():signal.signal(s,h)

class Engine:
    def run(self,args,*,input=None,extra=None,timeout=60):
        # An empty private client configuration prevents ~/.docker proxy/auth
        # settings from injecting environment into containers. No registry pulls.
        DOCKER_CONFIG.mkdir(parents=True,exist_ok=True,mode=0o700)
        require(not DOCKER_CONFIG.is_symlink() and DOCKER_CONFIG.stat().st_uid==os.getuid() and stat.S_IMODE(DOCKER_CONFIG.stat().st_mode)==0o700,'UNSAFE_DOCKER_CLIENT_CONFIG')
        config=DOCKER_CONFIG/'config.json'
        if not config.exists():atomic(config,encoded({'auths':{}}))
        require(not config.is_symlink() and json.loads(config.read_text())=={'auths':{}},'DOCKER_CLIENT_CONFIG_CHANGED')
        try:return subprocess.run(DOCKER+args,input=input,capture_output=True,env=environment(extra),timeout=timeout,check=True).stdout
        except (subprocess.SubprocessError,OSError):raise SafetyError('DOCKER_OPERATION_UNCERTAIN') from None
    def ok(self,args):
        self.run(args)
        return True  # A healthy fresh broker can successfully return an empty topic list.
    def json(self,args):return json.loads(self.run(args))
    def inspect(self,identifier):return self.json(['inspect',identifier])[0]
    def images(self):
        result={}
        for name,image in IMAGES.items():
            try:info=self.json(['image','inspect',image])[0]
            except SafetyError:raise SafetyError('REQUIRED_CACHED_IMAGE_MISSING_'+name.upper()) from None
            dangerous=('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','SPRING_','HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy','AWS_','STRIPE_','JWT_SECRET')
            for entry in info.get('Config',{}).get('Env',[]):
                key,_,value=entry.partition('=')
                require(not value or not any(key.startswith(prefix) for prefix in dangerous),'UNSAFE_BASE_IMAGE_ENVIRONMENT')
            result[name]=info['Id']
        return result


def artifacts(engine):
    inputs={}
    for name,module in MODULES.items():
        jar=ROOT/module/'target'/f'{module}-0.0.1-SNAPSHOT.jar';require(jar.is_file(),'MISSING_JAR_'+name.upper())
        require(not any(p.stat().st_mtime>jar.stat().st_mtime for p in (ROOT/module/'src/main').rglob('*') if p.is_file()),'STALE_JAR_'+name.upper())
        inputs[name]=digest(jar)
    launcher=ROOT/'payment-service/src/test/java/com/orderflow/payment/integration/PaymentIntegrationApplication.java'
    agent=ROOT/'scripts/test-plans/Phase5Agent.java'
    inputs.update(launcher=digest(launcher),agent=digest(agent),compiler=digest(JAVA/'javac'))
    bundle=hashlib.sha256(encoded(inputs)).hexdigest();directory=CACHE/bundle
    stamp=directory/'hashes.json'
    if not stamp.exists():
        require(not directory.exists(),'INCOMPLETE_ARTIFACT_SNAPSHOT');directory.mkdir(parents=True,mode=0o700)
        try:
            for name,module in MODULES.items():shutil.copyfile(ROOT/module/'target'/f'{module}-0.0.1-SNAPSHOT.jar',directory/(name+'.jar'))
            classes=directory/'payment-classes';libs=directory/'libs';compiled=directory/'test-classes'
            for path in (classes,libs,compiled):path.mkdir()
            with zipfile.ZipFile(directory/'payment.jar') as archive:
                for member in archive.namelist():
                    if member.startswith('BOOT-INF/classes/') and not member.endswith('/'):
                        relative=Path(member.removeprefix('BOOT-INF/classes/'));require('..' not in relative.parts,'BAD_JAR_MEMBER')
                        target=classes/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(member))
                    elif member.startswith('BOOT-INF/lib/') and member.endswith('.jar'):(libs/Path(member).name).write_bytes(archive.read(member))
            command=[str(JAVA/'javac'),'-proc:none','--release','21','--add-modules','jdk.httpserver','-cp',str(classes)+':'+str(libs/'*'),'-d',str(compiled),str(launcher),str(agent)]
            try:subprocess.run(command,env=environment(),capture_output=True,check=True,timeout=90)
            except (subprocess.SubprocessError,OSError):raise SafetyError('TEST_LAUNCHER_COMPILATION_FAILED') from None
            hashes={str(p.relative_to(directory)):digest(p) for p in directory.rglob('*') if p.is_file()}
            atomic(stamp,encoded(hashes))
            for p in directory.rglob('*'):p.chmod(0o500 if p.is_dir() else 0o400)
        except BaseException:
            # Preserve failed compiler evidence as an incomplete, non-executable snapshot.
            raise
    hashes=json.loads(stamp.read_text())
    verify_artifacts(directory,hashes)
    return {'directory':str(directory),'bundle':bundle,'files':hashes,'inputs':inputs,'images':engine.images()}

def verify_artifacts(directory,hashes):
    directory=Path(directory)
    require(set(hashes)=={str(p.relative_to(directory)) for p in directory.rglob('*') if p.is_file() and str(p.relative_to(directory))!='hashes.json'},'ARTIFACT_SET_CHANGED')
    for relative,expected in hashes.items():
        path=directory/relative;require(not path.is_symlink() and path.resolve().is_relative_to(directory.resolve()) and digest(path)==expected,'ARTIFACT_HASH_MISMATCH')


def compose_spec(run, snapshot, state):
    project='orderflow-buy-'+uuid.UUID(run).hex;label={'orderflow.test.run':run}
    logging={'driver':'none'};images=snapshot['images'];mount=str(Path(snapshot['directory']).resolve())+':/snapshot:ro'
    base={'image':images['java'],'pull_policy':'never','user':f'{os.getuid()}:{os.getgid()}', 'read_only':True,'tmpfs':['/tmp:mode=1777'], 'cap_drop':['ALL'],'security_opt':['no-new-privileges:true'],'logging':logging,'labels':label}
    agentcp='/snapshot/test-classes:/snapshot/libs/*'
    services={
      'postgres':{'image':images['postgres'],'pull_policy':'never','environment':{'POSTGRES_USER':'postgres','POSTGRES_DB':'of_'+uuid.UUID(run).hex,'POSTGRES_HOST_AUTH_METHOD':'trust'},'volumes':['pgdata:/var/lib/postgresql/data'],'networks':['private'],'logging':logging,'labels':label},
      'redis':{'image':images['redis'],'pull_policy':'never','networks':['private'],'logging':logging,'labels':label},
      'kafka':{'image':images['kafka'],'pull_policy':'never','environment':{'KAFKA_NODE_ID':'1','KAFKA_PROCESS_ROLES':'broker,controller','KAFKA_CONTROLLER_QUORUM_VOTERS':'1@kafka:9093','KAFKA_LISTENERS':'PLAINTEXT://:9092,CONTROLLER://:9093','KAFKA_ADVERTISED_LISTENERS':'PLAINTEXT://kafka:9092','KAFKA_CONTROLLER_LISTENER_NAMES':'CONTROLLER','KAFKA_LISTENER_SECURITY_PROTOCOL_MAP':'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT','KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR':'1','KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR':'1','KAFKA_TRANSACTION_STATE_LOG_MIN_ISR':'1','KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS':'0'},'volumes':['kafkadata:/var/lib/kafka/data'],'networks':['private'],'logging':logging,'labels':label},
      'runner':{**copy.deepcopy(base),'networks':['private'],'volumes':[mount,str(Path(state).resolve())+':/state:rw'], 'environment':{'PHASE5_RUN_ID':run,'PHASE5_RUN_KEY':'${PHASE5_RUN_KEY:?}'},'entrypoint':['/opt/java/openjdk/bin/java','-Xmx128m','-XX:ActiveProcessorCount=2','--add-modules','jdk.httpserver','-cp',agentcp,'Phase5Agent','server']}}
    for service,port in SERVICES.items():
        args=[f'--server.port={port}','--server.address=127.0.0.1','--eureka.client.enabled='+('true' if service=='discovery' else 'false'),
          '--spring.datasource.url=jdbc:postgresql://postgres:5432/of_'+uuid.UUID(run).hex,'--spring.datasource.username=postgres','--spring.datasource.password=',
          '--spring.data.redis.host=redis','--spring.data.redis.port=6379','--spring.kafka.bootstrap-servers=kafka:9092','--spring.kafka.consumer.auto-offset-reset=earliest',
          '--spring.jpa.show-sql=false','--orderflow.recovery.enabled=false','--management.info.env.enabled=true',f'--info.phase5.run={run}',f'--info.phase5.artifact={snapshot["inputs"][service]}']
        for dest,dport in SERVICES.items():
            if dest!='discovery':args.append(f'--spring.cloud.discovery.client.simple.instances.{MODULES[dest].upper()}[0].uri=http://127.0.0.1:{dport}')
        start=['/opt/java/openjdk/bin/java','-Xmx256m','-XX:ActiveProcessorCount=2']
        if service=='payment':start+=['-Dphase3.stripe-mock-url=http://127.0.0.1:18086','-cp','/snapshot/test-classes:/snapshot/payment-classes:/snapshot/libs/*','com.orderflow.payment.integration.PaymentIntegrationApplication']
        else:start+=['-jar','/snapshot/'+service+'.jar']
        services[service]={**copy.deepcopy(base),'network_mode':'service:runner','volumes':[mount], 'environment':{'PHASE5_RUN_ID':run,'JWT_SECRET':'${PHASE5_JWT_KEY:?}','STRIPE_SECRET_KEY':'${PHASE5_STRIPE_KEY:?}','STRIPE_WEBHOOK_SECRET':'${PHASE5_WEBHOOK_KEY:?}','DB_PASSWORD':''},'entrypoint':start+args}
    return {'name':project,'services':services,'networks':{'private':{'internal':True,'labels':label}},'volumes':{'pgdata':{'labels':label},'kafkadata':{'labels':label}}}


def validate_compose(spec, run):
    require(set(spec['networks'])=={'private'} and spec['networks']['private'].get('internal') is True and not spec['networks']['private'].get('external'),'NETWORK_NOT_INTERNAL')
    require(set(spec['services'])==set(SERVICES)|{'runner','postgres','redis','kafka'},'UNEXPECTED_SERVICE')
    for name,service in spec['services'].items():
        require(not service.get('ports') and not service.get('build') and not service.get('privileged') and service['logging']['driver']=='none','UNSAFE_CONTAINER_CONFIG')
        if name not in SERVICES:require(service.get('networks')==['private'],'UNEXPECTED_NETWORK')
        require(service['labels']['orderflow.test.run']==run and service['image'].startswith('sha256:') and service['pull_policy']=='never','UNPINNED_IMAGE_OR_OWNER')
        if name in SERVICES:require(service['network_mode']=='service:runner' and '--server.address=127.0.0.1' in service['entrypoint'],'APPLICATION_NOT_LOOPBACK')
    require(spec['services']['runner']['networks']==['private'],'WRONG_RUNNER_NETWORK')

class Controller:
    def __init__(self,journal,engine=None,stop=None):
        self.j=journal;self.engine=engine or Engine();self.stop=stop or StopFlag();self.redactor=Redactor();self.observers=[];self.seed=None;self.shutting_down=False;self.namespace_reset=False;self.image_configs={};self.log_threads=[]
    def compose(self,args):
        extra=self.key_environment() if self.seed else {k:'phase5_preflight_placeholder' for k in ('PHASE5_RUN_KEY','PHASE5_JWT_KEY','PHASE5_STRIPE_KEY','PHASE5_WEBHOOK_KEY')}
        return self.engine.run(['compose','--env-file','/dev/null','-p',self.j.data['project'],'-f',str(self.j.directory/'compose.json')]+args,extra=extra,timeout=90)
    def key_environment(self):
        require(self.seed is not None,'RUN_KEY_UNAVAILABLE')
        values={'PHASE5_RUN_KEY':self.seed,'PHASE5_JWT_KEY':derived(self.seed,'jwt'),'PHASE5_STRIPE_KEY':'phase5_mock_'+derived(self.seed,'stripe'),'PHASE5_WEBHOOK_KEY':derived(self.seed,'webhook')}
        for v in values.values():self.redactor.add(v)
        self.redactor.add('phase5_password_'+derived(self.seed,'password'))
        return values
    def configuration(self,service,info):
        expected=self.j.data['spec']['services'][service]
        image=info['Image']
        if image not in self.image_configs:self.image_configs[image]=self.engine.json(['image','inspect',image])[0]['Config']
        base=self.image_configs[image]
        require(info['Config'].get('Entrypoint')==expected.get('entrypoint',base.get('Entrypoint')),'CHILD_ENTRYPOINT_MISMATCH')
        expected_cmd=[] if 'entrypoint' in expected else base.get('Cmd')
        require((info['Config'].get('Cmd') or [])==(expected_cmd or []),'CHILD_COMMAND_MISMATCH')
        actual=dict(v.split('=',1) for v in info['Config'].get('Env',[]) if '=' in v)
        values=dict(v.split('=',1) for v in base.get('Env',[]) if '=' in v)
        keys=self.key_environment() if self.seed else {}
        for key,value in expected.get('environment',{}).items():
            if value.startswith('${'):
                variable=value[2:].split(':',1)[0]
                if variable not in keys:return  # runner key is verified/adopted below, then rechecked
                value=keys[variable]
            values[key]=value
        require(actual==values,'CHILD_ENVIRONMENT_MISMATCH')
    def containers(self):
        raw=self.engine.run(['ps','-aq','--filter','label=com.docker.compose.project='+self.j.data['project']]).decode().split()
        result={}
        if raw:require('compose-create' in self.j.data['operations'],'PROJECT_EXISTS_WITHOUT_CREATION_INTENT')
        for identifier in raw:
            info=self.engine.inspect(identifier);service=info['Config']['Labels'].get('com.docker.compose.service')
            require(service in self.j.data['spec']['services'] and service not in result,'UNEXPECTED_PROJECT_CONTAINER')
            expected=self.j.data['spec']['services'][service]
            require(info['Config']['Labels'].get('orderflow.test.run')==self.j.data['run'] and info['Image']==expected['image'],'CONTAINER_OWNERSHIP_MISMATCH')
            require(info['HostConfig']['LogConfig']['Type']=='none' and not info['HostConfig'].get('PortBindings'),'CONTAINER_EXPOSURE_OR_RAW_LOGGING')
            require(info['Config']['Labels'].get('com.docker.compose.project')==self.j.data['project'],'CONTAINER_PROJECT_MISMATCH')
            if service in SERVICES:
                require(info['HostConfig']['NetworkMode'].startswith('container:'),'WRONG_NETWORK_NAMESPACE')
            for mount in info.get('Mounts',[]):
                if mount['Destination']=='/snapshot':require(not mount['RW'] and Path(mount['Source']).resolve()==Path(self.j.data['snapshot']['directory']).resolve(),'WRONG_ARTIFACT_MOUNT')
            result[service]=info
        if 'runner' in result:
            require(result['runner']['HostConfig']['NetworkMode']==self.j.data['project']+'_private','WRONG_RUNNER_NETWORK')
            networks=result['runner']['NetworkSettings']['Networks']
            require(len(networks)<=1 and (len(networks)==1 or not result['runner']['State']['Running']),'RUNNER_HAS_ADDITIONAL_NETWORK')
            old_pid=self.j.data['containers'].get('runner',{}).get('pid',0)
            if old_pid and old_pid!=result['runner']['State']['Pid']:self.namespace_reset=True
            for service,info in result.items():
                if service in SERVICES:require(info['HostConfig']['NetworkMode']=='container:'+result['runner']['Id'],'WRONG_RUNNER_IDENTITY')
            networks=result['runner']['NetworkSettings']['Networks']
            references=[v.get('NetworkID') or k for k,v in networks.items()] or [self.j.data['project']+'_private']
            for reference in references:
                network_info=self.engine.json(['network','inspect',reference])[0]
                require(network_info['Internal'] and network_info['Labels'].get('orderflow.test.run')==self.j.data['run'],'NETWORK_ISOLATION_LOST')
            if self.seed is None:
                entries=dict(v.split('=',1) for v in result['runner']['Config']['Env'] if '=' in v)
                self.seed=entries.get('PHASE5_RUN_KEY');require(self.seed is not None,'RUN_KEY_UNAVAILABLE');self.key_environment()
        for service,info in result.items():self.configuration(service,info)
        self.j.data['containers']={s:{'id':i['Id'],'image':i['Image'],'pid':i['State']['Pid']} for s,i in result.items()};self.j.save()
        return result
    def identity(self,service,running=False):
        record=self.j.data['containers'].get(service)
        if not record:
            self.containers();record=self.j.data['containers'].get(service)
        require(record is not None,'MISSING_OWNED_CONTAINER')
        info=self.engine.inspect(record['id']);expected=self.j.data['spec']['services'][service]
        labels=info['Config']['Labels']
        require(info['Id']==record['id'] and labels.get('orderflow.test.run')==self.j.data['run'] and labels.get('com.docker.compose.project')==self.j.data['project'] and labels.get('com.docker.compose.service')==service and info['Image']==expected['image'],'CONTAINER_OWNERSHIP_MISMATCH')
        require(info['HostConfig']['LogConfig']['Type']=='none' and not info['HostConfig'].get('PortBindings'),'CONTAINER_EXPOSURE_OR_RAW_LOGGING')
        if service in SERVICES:require(info['HostConfig']['NetworkMode']=='container:'+self.j.data['containers']['runner']['id'],'WRONG_RUNNER_IDENTITY')
        if service=='runner':
            require(info['HostConfig']['NetworkMode']==self.j.data['project']+'_private','WRONG_RUNNER_NETWORK')
            if info['State']['Running']:require(len(info['NetworkSettings']['Networks'])==1,'RUNNER_HAS_ADDITIONAL_NETWORK')
        for mount in info.get('Mounts',[]):
            if mount['Destination']=='/snapshot':require(not mount['RW'] and Path(mount['Source']).resolve()==Path(self.j.data['snapshot']['directory']).resolve(),'WRONG_ARTIFACT_MOUNT')
        self.configuration(service,info)
        if running:require(info['State']['Running'],'OWNED_CHILD_EXITED_'+service.upper())
        return info
    def attach(self,identifier,service):
        process=subprocess.Popen(DOCKER+['attach','--no-stdin','--sig-proxy=false',identifier],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env=environment(),start_new_session=True)
        self.observers.append(process)
        self.j.data.setdefault('observers',[]).append({'pid':process.pid,'service':service});self.j.save()
        thread=threading.Thread(target=self.redactor.stream,args=(process.stdout,self.j.directory/(service+'.log')),daemon=True);thread.start();self.log_threads.append(thread)
    def start_service(self,service):
        info=self.identity(service)
        self.j.intent('start-'+service,container=info['Id'])
        if not info['State']['Running']:self.engine.run(['start',info['Id']])
        current=self.identity(service,True);self.j.data['containers'][service]['pid']=current['State']['Pid'];self.j.save()
        self.attach(info['Id'],service);self.j.done('start-'+service);self.stop.check()
    def provision(self):
        self.j.data['status']='provisioning';self.j.save()
        require(digest(self.j.directory/'compose.json')==self.j.data['compose_hash'],'COMPOSE_HASH_MISMATCH')
        verify_artifacts(self.j.data['snapshot']['directory'],self.j.data['snapshot']['files'])
        if 'compose-create' not in self.j.data['operations']:
            for kind in ('network','volume'):
                require(not self.engine.run([kind,'ls','-q','--filter','label=com.docker.compose.project='+self.j.data['project']]).strip(),'PROJECT_RESOURCES_ALREADY_EXIST')
        existing=self.containers()
        if self.seed is None:
            require(not any(name.startswith(('auth-','product-','inventory-','buy','webhook')) for name in self.j.data['operations']),'RUN_KEY_LOST_AFTER_FIXTURES')
            # A crash before containers exist has not created API fixtures. A new
            # ephemeral key is safe; once created, recover it only from owned runner.
            self.seed=secrets.token_hex(32);self.key_environment()
        self.j.intent('compose-create',project=self.j.data['project'])
        self.compose(['create','--no-build','--pull','never']);self.containers();self.j.done('compose-create');self.stop.check()
        for service in ('postgres','redis','kafka'):self.start_service(service)
        self.wait(lambda:self.engine.ok(['exec',self.identity('postgres',True)['Id'],'pg_isready','-U','postgres','-d',self.j.data['database']]),60)
        self.wait(lambda:self.engine.run(['exec',self.identity('redis',True)['Id'],'redis-cli','ping']).strip()==b'PONG',60)
        self.wait(lambda:self.engine.ok(['exec',self.identity('kafka',True)['Id'],'/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','localhost:9092','--list']),120)
        self.ensure_database_identity()
        current=self.containers()
        if (self.namespace_reset or not current['runner']['State']['Running']) and any(current[s]['State']['Running'] for s in SERVICES if s in current):
            require(not self.shutdown(False),'CHILD_SHUTDOWN_FAILED')
        self.namespace_reset=False
        self.start_service('runner')
        self.wait(lambda:self.rpc('mock','/__state',check=False)['status']==200,60)
        for service in SERVICES:self.start_service(service)
        for service in SERVICES:
            def ready():
                self.identity(service,True)
                response=self.rpc(service,'/' if service=='discovery' else '/actuator/health',check=False)
                if response['status']!=200:return False
                if service!='discovery':
                    if response['body'].get('status')!='UP':return False
                    info=self.rpc(service,'/actuator/info',check=False)
                    return info['body'].get('phase5',{})=={'run':self.j.data['run'],'artifact':self.j.data['snapshot']['inputs'][service]}
                return True
            self.wait(ready,240)
        state=self.rpc('mock','/__state')['body'];require(state['run']==self.j.data['run'] and state['probed'],'STRIPE_MOCK_NOT_ATTESTED')
        self.j.data['status']='ready';self.j.step('ready')
    def wait(self,operation,seconds):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            self.stop.check()
            try:
                if operation():return
            except SafetyError as error:
                if str(error).startswith(('CONTAINER_','WRONG_','NETWORK_','OWNED_CHILD_EXITED_')):raise
            time.sleep(.5)
        raise SafetyError('BOUNDED_WAIT_EXHAUSTED')
    def rpc(self,service,path,method='GET',body=None,headers=None,check=True):
        self.stop.check()
        runner=self.identity('runner',True)['Id']
        if service in SERVICES:self.identity(service,True)
        request={'service':service,'path':path,'method':method,'body':body or {},'headers':headers or {}}
        raw=self.engine.run(['exec','-i',runner,'/opt/java/openjdk/bin/java','-Xmx64m','-XX:ActiveProcessorCount=2','--add-modules','jdk.httpserver','-cp','/snapshot/test-classes:/snapshot/libs/*','Phase5Agent','rpc'],input=encoded(request),timeout=30)
        response=json.loads(raw)
        response['headers']={k.lower():v for k,v in response.get('headers',{}).items()}
        self.remember(response.get('body'))
        if check:require(response['status'] in (200,201,202),'HTTP_OPERATION_FAILED')
        return response
    def remember(self,value):
        if isinstance(value,dict):
            for key,item in value.items():
                if key.lower() in ('accesstoken','clientsecret','client_secret','password') and isinstance(item,str):self.redactor.add(item)
                else:self.remember(item)
        elif isinstance(value,list):
            for item in value:self.remember(item)
    def sql(self,statement):
        identifier=self.identity('postgres',True)['Id']
        return self.engine.run(['exec','-i',identifier,'psql','-X','-U','postgres','-d',self.j.data['database'],'-At','-q','-v','ON_ERROR_STOP=1'],input=statement.encode(),timeout=30).decode().strip()
    def query(self,statement):
        return json.loads(self.sql('BEGIN READ ONLY; '+statement+'; COMMIT;'))
    def ensure_database_identity(self):
        run=self.j.data['run'];operation=self.j.intent('database-identity',run=run)
        exists=self.sql("SELECT to_regclass('public.phase5_identity') IS NOT NULL;")=='t'
        if not exists:
            require(not self.j.data.get('database_initialized'),'DATABASE_IDENTITY_LOST')
            count=int(self.sql("SELECT count(*) FROM pg_tables WHERE schemaname='public';"))
            require(count==0,'DATABASE_NOT_EMPTY_AT_CREATION')
            self.sql("BEGIN; CREATE TABLE phase5_identity(run_id uuid PRIMARY KEY); INSERT INTO phase5_identity VALUES ('"+run+"'); COMMIT;")
        identity=self.query('SELECT coalesce(json_agg(run_id),\'[]\') FROM phase5_identity')
        require(identity==[run],'DATABASE_OWNERSHIP_MISMATCH')
        self.j.data['database_initialized']=True;self.j.done('database-identity')
    def verify_database(self):
        require(self.query('SELECT coalesce(json_agg(run_id),\'[]\') FROM phase5_identity')==[self.j.data['run']],'DATABASE_OWNERSHIP_MISMATCH')
    def rows(self,table):
        require(table in ('users','products','inventories','checkout_attempts','orders','payments','stock_reservations','order_items','stock_reservation_items','order_notifications','legacy_payment_effects'),'TABLE_NOT_ALLOWED')
        if self.sql("SELECT to_regclass('public."+table+"') IS NOT NULL;")!='t':return []
        return self.query('SELECT coalesce(json_agg(t),\'[]\') FROM '+table+' t')
    def reconcile(self):
        self.verify_database();operations=self.j.data['operations'];resources=self.j.data['resources']
        # Ownership comes from a new, initially empty, private database with run UUID
        # marker, private namespace, exact pre-recorded request and linked IDs.
        auth=operations.get('auth-register',{}).get('correlation',{})
        for row in self.rows('users'):
            require(row['email']==auth.get('email'),'UNEXPECTED_USER_IN_PRIVATE_DATABASE');self.j.resource('users',row['id'])
        expected_names={op['correlation']['name'] for name,op in operations.items() if name.startswith('product-')}
        for row in self.rows('products'):
            require(row['name'] in expected_names,'UNEXPECTED_PRODUCT_IN_PRIVATE_DATABASE');self.j.resource('products',row['id'])
        for row in self.rows('inventories'):
            require(row['product_id'] in resources.get('products',[]),'INVENTORY_OWNERSHIP_MISMATCH');self.j.resource('inventories',row['id'])
        correlation=operations.get('buy',{}).get('correlation',{})
        for row in self.rows('checkout_attempts'):
            require(row['user_id'] in resources.get('users',[]) and row['idempotency_key']==correlation.get('key'),'CHECKOUT_OWNERSHIP_MISMATCH')
            self.j.resource('attempts',row['id'])
            if row.get('order_id'):self.j.resource('orders',row['order_id'])
        for row in self.rows('orders'):
            require(row['id'] in resources.get('orders',[]) and row['user_id'] in resources.get('users',[]) and row['checkout_attempt_id'] in resources.get('attempts',[]),'ORDER_OWNERSHIP_MISMATCH')
        for row in self.rows('payments'):
            require(row['order_id'] in resources.get('orders',[]) and row['user_id'] in resources.get('users',[]),'PAYMENT_OWNERSHIP_MISMATCH');self.j.resource('payments',row['id'])
        for row in self.rows('stock_reservations'):
            require(row['order_id'] in resources.get('orders',[]),'RESERVATION_OWNERSHIP_MISMATCH');self.j.resource('reservations',row['order_id'])
        for row in self.rows('order_items'):
            require(row['order_id'] in resources.get('orders',[]) and row['product_id'] in resources.get('products',[]),'ORDER_ITEM_OWNERSHIP_MISMATCH')
        for row in self.rows('stock_reservation_items'):
            require(row['order_id'] in resources.get('reservations',[]) and row['product_id'] in resources.get('products',[]),'RESERVATION_ITEM_OWNERSHIP_MISMATCH')
        for table in ('order_notifications','legacy_payment_effects'):
            for row in self.rows(table):require(row['order_id'] in resources.get('orders',[]),'SIDE_EFFECT_OWNERSHIP_MISMATCH')
        self.j.step('reconciled')
    def api(self,path,method='GET',body=None,status=200,token=None,headers=None):
        values=dict(headers or {})
        if token:values['Authorization']='Bearer '+token
        response=self.rpc('gateway',path,method,body,values,check=False)
        require(response['status'] in (status if isinstance(status,tuple) else (status,)),'UNEXPECTED_HTTP_STATUS')
        return response
    def scenario(self):
        self.reconcile();run=self.j.data['run'];r=self.j.data['resources']
        correlation=self.j.intent('auth-register',email='phase5-'+uuid.UUID(run).hex+'@example.test')
        password='phase5_password_'+derived(self.seed,'password');self.redactor.add(password)
        if not r.get('users'):
            value=self.api('/api/v1/auth/register','POST',{'firstName':'Checkout','lastName':'Fixture','email':correlation['email'],'password':password},201)['body']
            self.j.resource('users',value['id'])
        self.j.done('auth-register');user=r['users'][0]
        self.j.intent('auth-login',user=user)
        auth=self.api('/api/v1/auth/login','POST',{'email':correlation['email'],'password':password})['body']
        token=auth['accessToken'];self.redactor.add(token);require(auth['tokenType']=='Bearer','UNEXPECTED_AUTH_SCHEME');self.j.done('auth-login')
        product_ids=[]
        for index,price in enumerate((12.5,20)):
            name='product-'+str(index);correlation=self.j.intent(name,name='phase5-'+uuid.UUID(run).hex+'-'+str(index),price=price)
            existing=[p for p in self.rows('products') if p['name']==correlation['name']]
            if existing:product=uid(existing[0]['id'])
            else:
                value=self.api('/api/v1/products','POST',{'name':correlation['name'],'price':price,'stock':10,'category':'test'},201,token)['body'];product=self.j.resource('products',value['id'])
            self.j.resource('products',product);self.j.done(name);product_ids.append(product)
            inventory_name='inventory-'+str(index);self.j.intent(inventory_name,product=product)
            existing=[p for p in self.rows('inventories') if p['product_id']==product]
            if not existing:
                value=self.api('/api/v1/inventory','POST',{'productId':product,'availableQuantity':10},201,token)['body'];self.j.resource('inventories',value['id'])
            else:self.j.resource('inventories',existing[0]['id'])
            self.j.done(inventory_name)
        a,b=product_ids
        body={'items':[{'productId':a,'quantity':2},{'productId':b,'quantity':1}]}
        correlation=self.j.intent('buy',key=str(uuid.uuid4()),products=product_ids,quantities=[2,1])
        key=correlation['key'];headers={'Idempotency-Key':key,'X-User-Id':str(uuid.uuid4())}
        existing_orders=self.rows('orders');already_confirmed=bool(existing_orders and existing_orders[0]['status']=='CONFIRMED')
        if already_confirmed:require(self.j.data.get('initial_contract_verified') and 'webhook-success' in self.j.data['operations'],'UNPROVEN_TERMINAL_RESUME')
        response=self.api('/api/v1/buy','POST',body,(200,) if already_confirmed else (201,),token,headers)
        value=response['body'];self.record_checkout(value)
        require(value['totalAmount']==45 and value['currency']=='INR' and value['workflowStatus']=='COMPLETED','AUTHORITATIVE_TOTAL_OR_STATE_WRONG')
        require(value['payment']['initializationState']=='READY','PAYMENT_NOT_READY')
        if not already_confirmed:
            require(value['orderStatus']=='PENDING' and value['payment']['status']=='PENDING','INITIAL_PAYMENT_CONTRACT_WRONG')
            if not self.j.data.get('initial_contract_verified'):
                require(bool(value['payment']['clientSecret']),'INITIAL_PAYMENT_SECRET_MISSING')
            else:
                # A prior signed-success attempt may have settled the durable mock
                # before its webhook response was received. Never create a new intent.
                require(bool(value['payment']['clientSecret']) or 'webhook-success' in self.j.data['operations'],'UNEXPECTED_MISSING_CLIENT_SECRET')
        require(response['headers'].get('cache-control')==['no-store'],'MISSING_NO_STORE')
        require({x['productId']:(x['quantity'],x['unitPrice']) for x in value['items']}=={a:(2,12.5),b:(1,20)},'PRICE_SNAPSHOT_WRONG')
        for product,price in ((a,12.5),(b,20)):
            quote=self.api('/api/v1/products/'+product+'/checkout-price',token=token)['body'];require(quote['price']==price,'AUTHORITATIVE_PRICE_WRONG')
        self.check_state('CONFIRMED' if already_confirmed else 'PENDING')
        self.j.data['initial_contract_verified']=True;self.j.done('buy')
        self.j.intent('concurrent-replays',request_ids=[str(uuid.uuid4()) for _ in range(5)])
        def replay(_):return self.api('/api/v1/buy','POST',body,(200,) if already_confirmed else (201,202),token,headers)['body']
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:replies=list(pool.map(replay,range(5)))
        require(all(x['orderId']==value['orderId'] and x['checkoutAttemptId']==value['checkoutAttemptId'] for x in replies),'DUPLICATE_CHECKOUT_IDENTITY')
        self.check_state('CONFIRMED' if already_confirmed else 'PENDING');self.j.done('concurrent-replays')
        conflict=self.api('/api/v1/buy','POST',{'items':[{'productId':a,'quantity':3}]},409,token,headers)['body'];require(conflict['code']=='IDEMPOTENCY_CONFLICT','CONFLICT_NOT_ENFORCED')
        self.check_state('CONFIRMED' if already_confirmed else 'PENDING');self.j.step('payload-conflict')
        self.api('/api/v1/payments/webhook','POST',{},400,headers={'Stripe-Signature':'invalid'});self.j.step('invalid-signature')
        mock=self.rpc('mock','/__state')['body'];require(len(mock['intents'])==1,'DUPLICATE_STRIPE_INTENT')
        intent=next(iter(mock['intents'].values()));require(intent['metadata']['orderId']==value['orderId'] and intent['metadata']['paymentId']==value['payment']['id'] and intent['metadata']['userId']==user and intent['amount']==4500 and intent['currency']=='inr','STRIPE_SNAPSHOT_WRONG')
        event=self.j.intent('webhook-success',event=str(uuid.uuid4()),intent=intent['id'],order=value['orderId'])
        current=self.rpc('mock','/__control','POST',{'id':intent['id'],'status':'succeeded'})['body']
        payload={'id':'evt_'+uuid.UUID(event['event']).hex,'object':'event','type':'payment_intent.succeeded','data':{'object':current}}
        self.j.resource('webhook_events',event['event'])
        for _ in range(2):
            timestamp=str(int(time.time()));raw=json.dumps(payload,sort_keys=True,separators=(',',':'))
            # RPC uses Jackson serialization, so supply the identical serialized body
            # through the agent's rawBody transport field rather than reserialize.
            signature='t='+timestamp+',v1='+hmac.new(derived(self.seed,'webhook').encode(),(timestamp+'.'+raw).encode(),hashlib.sha256).hexdigest()
            self.signed_webhook(raw,signature)
        self.wait(lambda:bool(self.rows('orders') and self.rows('orders')[0]['status']=='CONFIRMED'),90)
        self.check_state('CONFIRMED');self.j.done('webhook-success')
        terminal=self.api('/api/v1/buy','POST',body,200,token,headers)['body']
        require(terminal['orderId']==value['orderId'] and terminal['payment']['id']==value['payment']['id'] and terminal['payment']['clientSecret'] is None,'TERMINAL_REPLAY_WRONG')
        payment=self.api('/api/v1/payments/order/'+value['orderId'],token=token)['body']
        require(payment['status']=='COMPLETED' and not payment.get('clientSecret'),'PAYMENT_READ_WRONG')
        self.check_state('CONFIRMED');self.j.data['outcome']='passed';self.j.step('verified')
    def signed_webhook(self,raw,signature):
        request={'service':'gateway','path':'/api/v1/payments/webhook','method':'POST','rawBody':raw,'headers':{'Stripe-Signature':signature}}
        runner=self.identity('runner',True)['Id'];self.identity('gateway',True)
        output=self.engine.run(['exec','-i',runner,'/opt/java/openjdk/bin/java','-Xmx64m','-XX:ActiveProcessorCount=2','--add-modules','jdk.httpserver','-cp','/snapshot/test-classes:/snapshot/libs/*','Phase5Agent','rpc'],input=encoded(request),timeout=30)
        require(json.loads(output)['status']==200,'SIGNED_WEBHOOK_REJECTED')
    def record_checkout(self,value):
        self.j.resource('attempts',value['checkoutAttemptId']);self.j.resource('orders',value['orderId']);self.j.resource('reservations',value['orderId']);self.j.resource('payments',value['payment']['id'])
        total=value.get('totalAmount');currency=value.get('currency')
        self.j.data['observations']={'total':total if isinstance(total,(int,float)) else 'INVALID','currency':currency if isinstance(currency,str) and re.fullmatch('[A-Z]{3}',currency) else 'INVALID','order_state':value.get('orderStatus'),'payment_state':value['payment'].get('status'),'initialization_state':value['payment'].get('initializationState'),'client_secret_present':bool(value['payment'].get('clientSecret'))}
        self.j.save();self.reconcile()
    def check_state(self,status):
        self.reconcile();resources=self.j.data['resources']
        require(all(len(resources.get(k,[]))==1 for k in ('users','attempts','orders','payments','reservations')),'DUPLICATE_DURABLE_RESOURCE')
        require(all(len(self.rows(t))==1 for t in ('users','orders','payments','stock_reservations','checkout_attempts')),'DUPLICATE_DATABASE_ROWS')
        order=self.rows('orders')[0];payment=self.rows('payments')[0];reservation=self.rows('stock_reservations')[0]
        require(order['status']==status and order['total_amount']==45 and order['currency']=='INR','ORDER_STATE_WRONG')
        require(payment['status']==('COMPLETED' if status=='CONFIRMED' else 'PENDING') and payment['amount']==45 and payment['currency']=='INR','PAYMENT_STATE_WRONG')
        require(reservation['state']==('DEDUCTED' if status=='CONFIRMED' else 'RESERVED'),'RESERVATION_STATE_WRONG')
        if status=='CONFIRMED':require(payment['terminal_verified'],'UNVERIFIED_PAYMENT')
        products=self.j.data['operations']['buy']['correlation']['products']
        inventory={p['product_id']:p for p in self.rows('inventories')}
        for product,available,reserved in ((products[0],8,0 if status=='CONFIRMED' else 2),(products[1],9,0 if status=='CONFIRMED' else 1)):
            require(inventory[product]['available_quantity']==available and inventory[product]['reserved_quantity']==reserved,'STOCK_EFFECT_WRONG')
    def shutdown(self,include_infrastructure=False):
        failures=[]
        try:self.containers()
        except Exception:failures.append('OWNERSHIP_SCAN_FAILED')
        containers=self.j.data['containers']
        services=list(SERVICES)+['runner']+(['kafka','redis','postgres'] if include_infrastructure else [])
        for service in services:
            try:
                if service in containers:
                    info=self.identity(service)
                    if info['State']['Running']:self.engine.run(['stop','--time','10',info['Id']],timeout=20)
            except Exception:failures.append(service)
        for process in self.observers:
            try:
                process.terminate();process.wait(timeout=3)
            except Exception:
                try:process.kill();process.wait(timeout=3)
                except Exception:failures.append('observer')
        for thread in self.log_threads:
            thread.join(timeout=3)
            if thread.is_alive():failures.append('log_observer')
        self.j.data['shutdown_failures']=failures;self.j.save();return failures
    def cleanup(self):
        containers=self.containers()
        if not containers:
            require(not any(name.startswith(('auth-','product-','inventory-','buy','webhook')) for name in self.j.data['operations']),'LOST_RUN_INFRASTRUCTURE')
            self.j.data['status']='cleaned';self.j.step('no-fixtures-created');return
        # Cleanup never starts application services or emits a webhook.
        if 'postgres' not in containers:raise SafetyError('MISSING_OWNED_DATABASE')
        if not containers['postgres']['State']['Running']:self.start_service('postgres')
        self.wait(lambda:self.engine.ok(['exec',self.identity('postgres',True)['Id'],'pg_isready','-U','postgres','-d',self.j.data['database']]),60)
        if not self.j.data.get('database_initialized'):
            # Only infrastructure could have been created before initialization.
            require(not any(name.startswith(('auth-','product-','inventory-','buy')) for name in self.j.data['operations']),'UNVERIFIED_DATABASE')
            require(not self.shutdown(True),'CHILD_SHUTDOWN_FAILED');self.j.data['status']='cleaned';self.j.step('no-api-fixtures');return
        self.reconcile();payments=self.rows('payments');orders=self.rows('orders');reservations=self.rows('stock_reservations')
        for payment in payments:
            require(payment['terminal_verified'] and payment['status'] in ('COMPLETED','FAILED'),'UNRESOLVED_PAYMENT_PRESERVED')
        for order in orders:
            require(order['status'] in ('CONFIRMED','CANCELLED'),'UNRESOLVED_ORDER_PRESERVED')
        for reservation in reservations:require(reservation['state'] in ('DEDUCTED','RELEASED'),'UNRESOLVED_RESERVATION_PRESERVED')
        require(not self.shutdown(False),'CHILD_SHUTDOWN_FAILED')
        self.reconcile()
        statement=cleanup_sql(self.j.data,self.rows)
        atomic(self.j.directory/'cleanup.sql',statement.encode());self.j.data['status']='cleaning';self.j.save()
        try:self.sql(statement)
        except SafetyError:
            # A Docker exec timeout may occur after COMMIT. Reconcile, don't assume rollback.
            self.j.data['status']='cleanup_uncertain';self.j.save()
            if any(self.rows(t) for t in dict.fromkeys([t for t,c in DELETE_ORDER]+['users','products','orders','payments','stock_reservations','checkout_attempts','inventories'])):raise
        require(not any(self.rows(t) for t in dict.fromkeys([t for t,c in DELETE_ORDER]+['users','products','orders','payments','stock_reservations','checkout_attempts','inventories'])),'CLEANUP_NOT_EMPTY')
        require(not self.shutdown(True),'CHILD_SHUTDOWN_FAILED');self.j.data['status']='cleaned';self.j.step('cleanup_verified')


def cleanup_sql(data, rows):
    resources=data['resources'];run=uid(data['run'])
    ids={kind:[uid(v) for v in values] for kind,values in resources.items()}
    allowed={'users':'users','products':'products','inventories':'inventories','attempts':'checkout_attempts','orders':'orders','payments':'payments','reservations':'stock_reservations'}
    for kind,table in allowed.items():
        column='order_id' if kind=='reservations' else 'id'
        require({r[column] for r in rows(table)} <= set(ids.get(kind,[])),'UNRECORDED_CLEANUP_TARGET')
    def values(kind):return ','.join("'"+v+"'" for v in ids.get(kind,[]))
    commands=['BEGIN;',"SELECT 1 / CASE WHEN count(*)=1 AND bool_and(run_id='"+run+"'::uuid) THEN 1 ELSE 0 END FROM phase5_identity;"]
    orders=values('orders')
    for table,column in DELETE_ORDER:
        if orders:commands.append('DELETE FROM '+table+' WHERE '+column+' IN ('+orders+');')
    for table,kind,column in [('orders','orders','id'),('inventories','inventories','id'),('products','products','id'),('users','users','id')]:
        value=values(kind)
        if value:commands.append('DELETE FROM '+table+' WHERE '+column+' IN ('+value+');')
    # Verify all owned resource tables before COMMIT; no global reset or broad DELETE.
    for table in dict.fromkeys([t for t,c in DELETE_ORDER]+list(allowed.values())):
        commands.append('SELECT 1 / CASE WHEN count(*)=0 THEN 1 ELSE 0 END FROM '+table+';')
    commands.append('COMMIT;');return '\n'.join(commands)+'\n'

@contextlib.contextmanager
def exclusive(root):
    root=Path(root);root.mkdir(parents=True,exist_ok=True,mode=0o700)
    require(not root.is_symlink() and root.stat().st_uid==os.getuid() and stat.S_IMODE(root.stat().st_mode)==0o700,'UNSAFE_RUN_ROOT')
    fd=os.open(root/'.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    try:
        try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise SafetyError('ANOTHER_CONTROLLER_ACTIVE') from None
        yield
    finally:os.close(fd)

def pending(root):
    values=[]
    for path in Path(root).glob('*/manifest.json'):
        j=Journal.load(root,path.parent.name)
        if j.data['status'] not in TERMINAL:values.append(j.data['run'])
    return values

def preview(snapshot):
    run='00000000-0000-4000-8000-000000000001'
    spec=compose_spec(run,snapshot,RUNS/run/'state');validate_compose(spec,run)
    return {'run':'NEW_UUID_AT_START','project':'orderflow-buy-<run UUID hex>','database':'of_<run UUID hex>',
      'services':list(spec['services']),'host_ports':[], 'application_loopback_ports':SERVICES,'stripe_mock':'http://127.0.0.1:18086 within private runner namespace',
      'network':'internal only; no external networks or published ports','images':snapshot['images'],
      'cleanup_tables':[t for t,c in DELETE_ORDER]+['orders','inventories','products','users'],
      'cleanup_scope':'validated exact run UUIDs only; terminal payments/orders/reservations required','run_manifest_contains_credentials':False}

class SafeParser(argparse.ArgumentParser):
    def error(self,message):raise SafetyError('INVALID_COMMAND_USE_HELP')

def main(argv=None):
    parser=SafeParser(description='Phase 5 isolated Buy Now harness. Default: dry-run (no services, APIs or cleanup).')
    parser.add_argument('operation',choices=('dry-run','start','inspect','resume','cleanup'),nargs='?',default='dry-run');parser.add_argument('run',nargs='?')
    args=parser.parse_args(argv)
    require(sys.flags.optimize==0,'PYTHON_OPTIMIZATION_NOT_ALLOWED');os.umask(0o077)
    if args.operation=='dry-run':
        engine=Engine();snapshot=artifacts(engine);plan=preview(snapshot)
        # Validate actual Compose parser with dummy placeholders, never start/create.
        import tempfile
        with tempfile.TemporaryDirectory(prefix='orderflow-phase5-plan-') as directory:
            file=Path(directory)/'compose.json';file.write_bytes(encoded(compose_spec('00000000-0000-4000-8000-000000000001',snapshot,Path(directory)/'state')))
            engine.run(['compose','--env-file','/dev/null','-p','orderflow-phase5-dry-run','-f',str(file),'config','--quiet'],extra={k:'phase5_placeholder' for k in ('PHASE5_RUN_KEY','PHASE5_JWT_KEY','PHASE5_STRIPE_KEY','PHASE5_WEBHOOK_KEY')})
        print(json.dumps(plan,indent=2));print('DRY_RUN_ONLY: no containers created/started; no HTTP requests, fixtures or database cleanup.');return 0
    if args.operation=='inspect':
        require(args.run is not None,'RUN_ID_REQUIRED');j=Journal.load(RUNS,args.run)
        print(json.dumps(j.data,indent=2));return 0
    with exclusive(RUNS):
        if args.operation=='start':
            require(args.run is None,'START_ALLOCATES_A_NEW_RUN_ID')
            unfinished=pending(RUNS)
            if unfinished:
                print('Incomplete run IDs: '+','.join(unfinished));raise SafetyError('INSPECT_OR_RESUME_EXISTING_RUN_FIRST')
            snapshot=artifacts(Engine());j=Journal.create(RUNS);(j.directory/'state').mkdir(mode=0o700)
            spec=compose_spec(j.data['run'],snapshot,j.directory/'state');validate_compose(spec,j.data['run'])
            j.data.update(snapshot=snapshot,spec=spec);atomic(j.directory/'compose.json',encoded(spec));j.data['compose_hash']=digest(j.directory/'compose.json');j.save()
        else:
            require(args.run is not None,'RUN_ID_REQUIRED');j=Journal.load(RUNS,args.run)
        if j.data['status']=='cleaned':print('RUN_ALREADY_CLEANED '+j.data['run']);return 0
        if 'spec' not in j.data:
            engine=Engine()
            require(not engine.run(['ps','-aq','--filter','label=com.docker.compose.project='+j.data['project']]).strip(),'MISSING_RUN_PROVENANCE')
            require('compose-create' not in j.data['operations'],'MISSING_RUN_PROVENANCE')
            if args.operation=='cleanup':
                j.data['status']='cleaned';j.step('allocation-only-cleanup');print('RUN_CLEANED '+j.data['run']);return 0
            snapshot=artifacts(engine);(j.directory/'state').mkdir(mode=0o700,exist_ok=True)
            spec=compose_spec(j.data['run'],snapshot,j.directory/'state');validate_compose(spec,j.data['run'])
            j.data.update(snapshot=snapshot,spec=spec);atomic(j.directory/'compose.json',encoded(spec));j.data['compose_hash']=digest(j.directory/'compose.json');j.save()
        controller=Controller(j)
        with controller.stop.installed():
            try:
                if args.operation=='cleanup':controller.cleanup()
                else:
                    controller.provision();controller.scenario();controller.cleanup()
                print('RUN_'+j.data['status'].upper()+' '+j.data['run']+' outcome='+j.data['outcome']);return 0
            except Exception as error:
                j.data['outcome']='interrupted' if isinstance(error,Interrupted) else 'failed';j.data['failure_code']=safe_code(error);j.data['status']='needs_recovery';j.save()
                # Best effort after ordinary failure, but never force unresolved stock/payment cleanup.
                controller.stop.requested=False
                try:controller.cleanup()
                except Exception:
                    j.data['status']='needs_recovery';j.data['cleanup_refused']=True;j.save()
                finally:controller.shutdown(False)
                print('RUN_FAILED '+j.data['run']+' status='+j.data['status']+'; inspect/resume/cleanup this run. No secret diagnostics printed.',file=sys.stderr);return 1

def safe_code(error):
    value=str(error) if isinstance(error,SafetyError) else ''
    return value if re.fullmatch(r'[A-Z0-9_]+',value) else 'UNEXPECTED_HARNESS_ERROR'

def cli():
    try:return main()
    except Exception as error:
        print('PHASE5_STOPPED: '+safe_code(error)+'. No secret exception text printed.',file=sys.stderr);return 1
