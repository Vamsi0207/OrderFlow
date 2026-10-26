import contextlib
import io
import json
import os
from pathlib import Path
import signal
import stat
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import phase5 as p

RUN='11111111-1111-4111-8111-111111111111'
U='22222222-2222-4222-8222-222222222222'
P='33333333-3333-4333-8333-333333333333'
O='44444444-4444-4444-8444-444444444444'
A='55555555-5555-4555-8555-555555555555'
I='66666666-6666-4666-8666-666666666666'
PAY='77777777-7777-4777-8777-777777777777'

class JournalTests(unittest.TestCase):
    def setUp(self):self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.j=p.Journal.create(self.root,RUN)
    def tearDown(self):self.temp.cleanup()
    def test_manifest_private_atomic_and_persistent(self):
        self.j.intent('buy',key=A);self.j.resource('orders',O)
        loaded=p.Journal.load(self.root,RUN)
        self.assertEqual(loaded.data['resources']['orders'],[O])
        self.assertEqual(stat.S_IMODE((self.j.directory/'manifest.json').stat().st_mode),0o600)
        self.assertEqual(stat.S_IMODE(self.j.directory.stat().st_mode),0o700)
    def test_intent_is_written_before_result_and_survives_failure(self):
        self.j.intent('product-0',name='correlation-before-http')
        self.assertEqual(p.Journal.load(self.root,RUN).data['operations']['product-0']['state'],'pending')
        self.j.intent('product-0',name='new-value-must-not-overwrite')
        self.assertEqual(self.j.data['operations']['product-0']['correlation']['name'],'correlation-before-http')
    def test_record_immediate_and_deduplicate(self):
        self.j.resource('users',U);self.j.resource('users',U)
        self.assertEqual(p.Journal.load(self.root,RUN).data['resources']['users'],[U])
    def test_incomplete_run_blocks_fresh_start_and_cleaned_does_not(self):
        self.assertEqual(p.pending(self.root),[RUN]);self.j.data['status']='cleaned';self.j.save();self.assertEqual(p.pending(self.root),[])
    def test_secret_fields_are_rejected(self):
        for field in ('password','token','clientSecret','api_key','authorization'):
            with self.subTest(field=field),self.assertRaises(p.SafetyError):p.no_secrets({field:'hidden'})
    def test_secret_values_are_rejected(self):
        for value in ('phase5_mock_'+'a'*64,'phase5_password_'+'b'*64,'eyJabc.def.signature'):
            with self.assertRaises(p.SafetyError):p.no_secrets({'value':value})
    def test_invalid_uuid_and_changed_identity_rejected(self):
        with self.assertRaises((ValueError,p.SafetyError)):self.j.resource('products',"'; DELETE FROM users;")
        self.j.data['project']='unrelated';self.j.save()
        with self.assertRaises(p.SafetyError):p.Journal.load(self.root,RUN)
    def test_symlink_manifest_rejected(self):
        path=self.j.directory/'manifest.json';path.unlink();path.symlink_to(self.root/'other')
        with self.assertRaises(p.SafetyError):self.j.save()

class EnvironmentAndLoggingTests(unittest.TestCase):
    def test_environment_is_allowlisted(self):
        with patch.dict(os.environ,{'JAVA_TOOL_OPTIONS':'bad','SPRING_APPLICATION_JSON':'bad','HTTP_PROXY':'bad','JWT_SECRET':'bad','AWS_SECRET_ACCESS_KEY':'bad','PYTHONOPTIMIZE':'1'}):
            env=p.environment()
        self.assertEqual(set(env),{'PATH','LANG','HOME'})
    def test_redacts_known_values_generated_passwords_and_jwts(self):
        red=p.Redactor();red.add('private_test_key')
        output=red.text('key=private_test_key password=phase5_password_'+'a'*64+' jwt=eyJabc.abc.def clientSecret=phase5_client_'+'b'*64)
        self.assertNotIn('private_test_key',output);self.assertNotIn('eyJabc',output);self.assertNotIn('phase5_password_',output);self.assertNotIn('phase5_client_',output)
    def test_cross_chunk_secret_never_written(self):
        class Chunks:
            def __init__(self):self.parts=iter([b'password=phase5_pass',b'word_'+b'a'*64+b'\n',b''])
            def read(self,n):return next(self.parts)
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/'safe.log';p.Redactor().stream(Chunks(),file)
            self.assertNotIn('phase5_password_',file.read_text());self.assertEqual(stat.S_IMODE(file.stat().st_mode),0o600)
    def test_oversized_line_discarded(self):
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/'log';p.Redactor().stream(io.BytesIO(b'x'*70000+b'password=hidden\n'),file)
            self.assertEqual(file.read_text(),'[OVERSIZED LINE SUPPRESSED]\n')

class IsolationTests(unittest.TestCase):
    def snapshot(self):return {'directory':'/tmp/readonly-snapshot','inputs':{k:'a'*64 for k in p.SERVICES},'images':{k:'sha256:'+'b'*64 for k in p.IMAGES}}
    def test_every_run_has_private_broker_database_network_and_no_host_ports(self):
        spec=p.compose_spec(RUN,self.snapshot(),'/tmp/private-state');p.validate_compose(spec,RUN)
        self.assertTrue(spec['networks']['private']['internal']);self.assertNotIn('external',spec['networks']['private'])
        for service in spec['services'].values():self.assertNotIn('ports',service);self.assertEqual(service['logging']['driver'],'none')
        self.assertIn(uuid_hex(RUN),spec['services']['postgres']['environment']['POSTGRES_DB'])
    def test_apps_loopback_runner_namespace_and_pinned_artifacts(self):
        spec=p.compose_spec(RUN,self.snapshot(),'/tmp/state')
        for service in p.SERVICES:
            app=spec['services'][service];self.assertEqual(app['network_mode'],'service:runner');self.assertIn('--server.address=127.0.0.1',app['entrypoint']);self.assertTrue(app['volumes'][0].endswith(':ro'))
        payment=spec['services']['payment']['entrypoint'];self.assertIn('-Dphase3.stripe-mock-url=http://127.0.0.1:18086',payment)
        self.assertIn('/snapshot/test-classes:/snapshot/payment-classes:/snapshot/libs/*',payment)
        self.assertNotIn('target/classes',' '.join(payment))
    def test_ports_and_external_network_are_rejected(self):
        spec=p.compose_spec(RUN,self.snapshot(),'/tmp/state');spec['services']['gateway']['ports']=['8080:19080']
        with self.assertRaises(p.SafetyError):p.validate_compose(spec,RUN)
        spec=p.compose_spec(RUN,self.snapshot(),'/tmp/state');spec['networks']['private']['internal']=False
        with self.assertRaises(p.SafetyError):p.validate_compose(spec,RUN)
    def test_dry_run_plan_has_no_cleanup_execution(self):
        value=p.preview(self.snapshot());self.assertEqual(value['host_ports'],[]);self.assertIn('terminal payments',value['cleanup_scope'])
    def test_artifact_tamper_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/'artifact';file.write_bytes(b'reviewed');hashes={'artifact':p.digest(file)}
            p.verify_artifacts(directory,hashes);file.write_bytes(b'tampered')
            with self.assertRaises(p.SafetyError):p.verify_artifacts(directory,hashes)
    def test_extra_artifact_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory)/'extra').write_text('unexpected')
            with self.assertRaises(p.SafetyError):p.verify_artifacts(directory,{})

class CleanupAndRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.j=p.Journal.create(self.temp.name,RUN)
        self.j.data['resources']={'users':[U],'products':[P],'inventories':[I],'orders':[O],'attempts':[A],'payments':[PAY],'reservations':[O]}
        self.tables={'users':[{'id':U,'email':'expected@example.test'}],'products':[{'id':P,'name':'expected-product'}],'inventories':[{'id':I,'product_id':P}],
          'orders':[{'id':O,'user_id':U,'checkout_attempt_id':A,'status':'CONFIRMED'}],
          'checkout_attempts':[{'id':A,'user_id':U,'idempotency_key':A,'order_id':O}],
          'payments':[{'id':PAY,'order_id':O,'user_id':U,'status':'COMPLETED','terminal_verified':True}],
          'stock_reservations':[{'order_id':O,'state':'DEDUCTED'}],'order_items':[{'order_id':O,'product_id':P}],
          'stock_reservation_items':[{'order_id':O,'product_id':P}],'order_notifications':[],'legacy_payment_effects':[]}
        self.j.intent('auth-register',email='expected@example.test');self.j.intent('product-0',name='expected-product');self.j.intent('buy',key=A)
    def tearDown(self):self.temp.cleanup()
    def rows(self,table):return self.tables.get(table,[])
    def controller(self):
        c=p.Controller(self.j,Mock());c.verify_database=Mock();c.rows=Mock(side_effect=self.rows);return c
    def test_cleanup_dependency_order_and_uuid_predicates(self):
        sql=p.cleanup_sql(self.j.data,self.rows)
        self.assertLess(sql.index('DELETE FROM stock_reservation_items'),sql.index('DELETE FROM stock_reservations'))
        self.assertLess(sql.index('DELETE FROM order_items'),sql.index('DELETE FROM orders WHERE'))
        self.assertLess(sql.index('DELETE FROM orders WHERE'),sql.index('DELETE FROM users'))
        for line in sql.splitlines():
            if line.startswith('DELETE'):self.assertIn(' WHERE ',line);self.assertIn(' IN (',line)
        self.assertTrue(sql.startswith('BEGIN;'));self.assertTrue(sql.endswith('COMMIT;\n'))
    def test_unrecorded_row_prevents_cleanup_sql(self):
        self.tables['users'].append({'id':P})
        with self.assertRaises(p.SafetyError):p.cleanup_sql(self.j.data,self.rows)
    def test_uuid_injection_rejected_before_sql(self):
        self.j.data['resources']['users']=["x'; drop database postgres;"]
        with self.assertRaises((ValueError,p.SafetyError)):p.cleanup_sql(self.j.data,self.rows)
    def test_lost_product_response_reconciled_using_private_identity_and_prejournal(self):
        self.j.data['resources']['products']=[];controller=self.controller();controller.reconcile()
        controller.verify_database.assert_called_once();self.assertEqual(self.j.data['resources']['products'],[P])
    def test_matching_name_without_private_database_identity_is_insufficient(self):
        c=self.controller();c.verify_database.side_effect=p.SafetyError('wrong identity')
        with self.assertRaises(p.SafetyError):c.reconcile()
        c.rows.assert_not_called()
    def test_unexpected_foreign_order_prevents_reconciliation(self):
        self.tables['orders'][0]['user_id']=P
        with self.assertRaises(p.SafetyError):self.controller().reconcile()
    def test_unexpected_product_prevents_reconciliation(self):
        self.tables['products'][0]['name']='different'
        with self.assertRaises(p.SafetyError):self.controller().reconcile()
    def test_cleanup_unresolved_payment_preserves_everything(self):
        c=self.controller();c.containers=Mock(return_value={'postgres':{'State':{'Running':True}}});c.wait=Mock();c.j.data['database_initialized']=True
        c.reconcile=Mock();c.shutdown=Mock(return_value=[]);c.sql=Mock();self.tables['payments'][0]['status']='PENDING'
        with self.assertRaisesRegex(p.SafetyError,'UNRESOLVED_PAYMENT'):c.cleanup()
        c.sql.assert_not_called();c.shutdown.assert_not_called()
    def test_cleanup_ambiguous_commit_rechecks_absence(self):
        c=self.controller();c.containers=Mock(return_value={'postgres':{'State':{'Running':True}}});c.wait=Mock();c.j.data['database_initialized']=True
        c.reconcile=Mock();c.shutdown=Mock(return_value=[])
        def sql(_):
            for table in self.tables:self.tables[table]=[]
            raise p.SafetyError('lost response AFTER commit')
        c.sql=Mock(side_effect=sql);c.cleanup();self.assertEqual(self.j.data['status'],'cleaned')
    def test_cleanup_ambiguous_commit_with_rows_preserves_recovery(self):
        c=self.controller();c.containers=Mock(return_value={'postgres':{'State':{'Running':True}}});c.wait=Mock();c.j.data['database_initialized']=True
        c.reconcile=Mock();c.shutdown=Mock(return_value=[]);c.sql=Mock(side_effect=p.SafetyError('lost response'))
        with self.assertRaises(p.SafetyError):c.cleanup()
        self.assertEqual(self.j.data['status'],'cleanup_uncertain')
    def test_empty_cleanup_is_idempotent(self):
        for table in self.tables:self.tables[table]=[]
        sql=p.cleanup_sql(self.j.data,self.rows);self.assertIn('COMMIT;',sql)

class ProcessAndSignalTests(unittest.TestCase):
    def test_signal_records_stop_without_interrupting_spawn_record(self):
        flag=p.StopFlag();flag.handler(signal.SIGTERM,None);self.assertTrue(flag.requested)
        with self.assertRaises(p.Interrupted):flag.check()
    def test_both_signal_handlers_restored(self):
        flag=p.StopFlag();old={s:signal.getsignal(s) for s in (signal.SIGINT,signal.SIGTERM)}
        with flag.installed():
            for s in old:self.assertEqual(signal.getsignal(s),flag.handler)
        for s in old:self.assertEqual(signal.getsignal(s),old[s])
    def test_one_shutdown_failure_does_not_skip_other_owned_children(self):
        with tempfile.TemporaryDirectory() as directory:
            j=p.Journal.create(directory,RUN);j.data['containers']={'gateway':{'id':'g'},'auth':{'id':'a'}}
            engine=Mock();engine.run.side_effect=[p.SafetyError('fail'),b'']
            c=p.Controller(j,engine);c.containers=Mock();c.identity=Mock(side_effect=lambda service:{'Id':j.data['containers'][service]['id'],'State':{'Running':True}})
            failures=c.shutdown();self.assertIn('gateway',failures);self.assertEqual(engine.run.call_count,2)
    def test_exited_child_never_gets_http(self):
        with tempfile.TemporaryDirectory() as directory:
            j=p.Journal.create(directory,RUN);c=p.Controller(j,Mock());c.identity=Mock(side_effect=p.SafetyError('OWNED_CHILD_EXITED_PAYMENT'))
            with self.assertRaises(p.SafetyError):c.rpc('payment','/actuator/health')
            c.engine.run.assert_not_called()
    def test_optimization_rejected(self):
        # Safety uses explicit require, not removable assert statements.
        source=Path(p.__file__).read_text();self.assertNotIn('\n        assert ',source)
        self.assertIn('PYTHON_OPTIMIZATION_NOT_ALLOWED',source)


def uuid_hex(value):return value.replace('-','')

class FailurePathAndStaticTests(unittest.TestCase):
    def test_scenario_failure_attempts_safe_cleanup_and_shutdown(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);j=p.Journal.create(root,RUN);j.data['spec']={'services':{}};j.save()
            controller=Mock();controller.stop=p.StopFlag();controller.scenario.side_effect=p.SafetyError('SIMULATED_ASSERTION')
            with patch.object(p,'RUNS',root),patch.object(p,'Controller',return_value=controller),contextlib.redirect_stderr(io.StringIO()):
                result=p.main(['resume',RUN])
            self.assertEqual(result,1);controller.cleanup.assert_called_once();controller.shutdown.assert_called_once_with(False)
            self.assertEqual(p.Journal.load(root,RUN).data['failure_code'],'SIMULATED_ASSERTION')
    def test_unresolved_failure_keeps_incomplete_run_blocker(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);j=p.Journal.create(root,RUN);j.data['spec']={'services':{}};j.save()
            controller=Mock();controller.stop=p.StopFlag();controller.scenario.side_effect=p.SafetyError('SIMULATED_ASSERTION');controller.cleanup.side_effect=p.SafetyError('UNRESOLVED_PAYMENT')
            with patch.object(p,'RUNS',root),patch.object(p,'Controller',return_value=controller),contextlib.redirect_stderr(io.StringIO()):p.main(['resume',RUN])
            self.assertEqual(p.pending(root),[RUN]);self.assertTrue(p.Journal.load(root,RUN).data['cleanup_refused'])
    def test_lost_buy_response_reconciles_order_and_payment(self):
        case=CleanupAndRecoveryTests();case.setUp()
        try:
            case.j.data['resources']['orders']=[];case.j.data['resources']['payments']=[];case.j.data['resources']['reservations']=[]
            case.controller().reconcile()
            self.assertEqual(case.j.data['resources']['orders'],[O]);self.assertEqual(case.j.data['resources']['payments'],[PAY]);self.assertEqual(case.j.data['resources']['reservations'],[O])
        finally:case.tearDown()
    def test_inspect_is_read_only_and_does_not_instantiate_engine(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);p.Journal.create(root,RUN)
            with patch.object(p,'RUNS',root),patch.object(p,'Engine') as engine,contextlib.redirect_stdout(io.StringIO()):self.assertEqual(p.main(['inspect',RUN]),0)
            engine.assert_not_called()
    def test_no_dynamic_source_execution_or_shell_in_main_harness(self):
        import ast
        tree=ast.parse(Path(p.__file__).read_text())
        for node in ast.walk(tree):
            if isinstance(node,ast.Call):
                if isinstance(node.func,ast.Name):self.assertNotIn(node.func.id,('exec','eval'))
                self.assertFalse(any(k.arg=='shell' and isinstance(k.value,ast.Constant) and k.value.value is True for k in node.keywords))
    def test_no_destructive_infrastructure_commands(self):
        source=Path(p.__file__).read_text()
        import re
        self.assertIsNone(re.search(r"\[\s*['\"](?:down|prune|rm|rmi)['\"]",source))
        self.assertNotIn('DROP DATABASE',source.upper());self.assertNotIn('TRUNCATE ',source.upper())
        self.assertNotIn('0.0.0.0',source)
    def test_sdk_probe_and_proxy_free_agent_guards_present(self):
        launcher=(p.ROOT/'payment-service/src/test/java/com/orderflow/payment/integration/PaymentIntegrationApplication.java').read_text()
        agent=(p.ROOT/'scripts/test-plans/Phase5Agent.java').read_text()
        self.assertLess(launcher.index('PaymentIntent.retrieve'),launcher.index('PaymentServiceApplication.main'))
        self.assertIn('uri.getPort()!=18086',launcher);self.assertIn('Stripe.getApiBase()',launcher)
        self.assertIn('Proxy.NO_PROXY',agent);self.assertIn('HttpClient.Redirect.NEVER',agent)
        self.assertIn('request.containsKey("rawBody")',agent)
    def test_resume_does_not_require_new_secret_for_already_settled_intent(self):
        source=Path(p.__file__).read_text();self.assertIn("if not self.j.data.get('initial_contract_verified'):",source)
        self.assertIn("'webhook-success' in self.j.data['operations']",source)
    def test_sensitive_exception_text_not_in_failure_code(self):
        self.assertEqual(p.safe_code(ValueError('password=must_not_print')),'UNEXPECTED_HARNESS_ERROR')
        self.assertEqual(p.safe_code(p.SafetyError('password=must_not_print')),'UNEXPECTED_HARNESS_ERROR')
        self.assertEqual(p.safe_code(p.SafetyError('KNOWN_SAFE_CODE')),'KNOWN_SAFE_CODE')

class ChildConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.j=p.Journal.create(self.temp.name,RUN)
        snapshot=IsolationTests().snapshot();self.j.data['spec']=p.compose_spec(RUN,snapshot,'/tmp/state')
        self.engine=Mock();self.engine.json.return_value=[{'Config':{'Env':[],'Cmd':[],'Entrypoint':[]}}]
        self.c=p.Controller(self.j,self.engine);self.c.seed='in-memory-unit-seed'
        expected=self.j.data['spec']['services']['payment']
        keys=self.c.key_environment();env={}
        for k,v in expected['environment'].items():env[k]=keys[v[2:].split(':',1)[0]] if v.startswith('${') else v
        self.info={'Image':'sha256:'+'b'*64,'Config':{'Entrypoint':expected['entrypoint'],'Cmd':[],'Env':[k+'='+v for k,v in env.items()]}}
    def tearDown(self):self.temp.cleanup()
    def test_actual_loaded_entrypoint_must_match_snapshot_command(self):
        self.c.configuration('payment',self.info);self.info['Config']['Entrypoint']=['unexpected-program']
        with self.assertRaisesRegex(p.SafetyError,'CHILD_ENTRYPOINT'):self.c.configuration('payment',self.info)
    def test_environment_injection_rejected_before_readiness(self):
        self.info['Config']['Env'].append('JAVA_TOOL_OPTIONS=unexpected')
        with self.assertRaisesRegex(p.SafetyError,'CHILD_ENVIRONMENT'):self.c.configuration('payment',self.info)


class InspectionAndPermissionsTests(unittest.TestCase):
    def test_inspect_works_while_controller_lock_is_held(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);p.Journal.create(root,RUN)
            with p.exclusive(root),patch.object(p,'RUNS',root),contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(p.main(['inspect',RUN]),0)
    def test_broadly_readable_manifest_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            j=p.Journal.create(directory,RUN);(j.directory/'manifest.json').chmod(0o644)
            with self.assertRaisesRegex(p.SafetyError,'UNSAFE_MANIFEST'):p.Journal.load(directory,RUN)


class EmptyBrokerHealthTests(unittest.TestCase):
    def test_successful_empty_topic_list_is_healthy(self):
        engine=p.Engine()
        with patch.object(engine,'run',return_value=b''):
            self.assertTrue(engine.ok(['exec','owned-kafka','kafka-topics','--list']))

if __name__=='__main__':unittest.main()
