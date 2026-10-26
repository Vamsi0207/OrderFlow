package com.orderflow.payment.integration;

/** Test-only launcher. Compiled fresh into a pinned snapshot, never a production jar. */
public class PaymentIntegrationApplication {
    public static void main(String[] args) throws Exception {
        var uri=java.net.URI.create(System.getProperty("phase3.stripe-mock-url"));
        if(!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()!=18086
                || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Pinned loopback mock required");
        String key=System.getenv("STRIPE_SECRET_KEY"),run=System.getenv("PHASE5_RUN_ID");
        if(key==null || !key.startsWith("phase5_mock_") || run==null)throw new IllegalStateException("Missing test identity");
        com.stripe.Stripe.overrideApiBase(uri.toString());
        com.stripe.Stripe.apiKey=key;
        if(!uri.toString().equals(com.stripe.Stripe.getApiBase()))throw new IllegalStateException("Mock override failed");
        // A real SDK read against the loopback-only agent must corroborate the run
        // BEFORE Spring starts and BEFORE the harness creates any user/product.
        var options=com.stripe.net.RequestOptions.builder().setConnectTimeout(2000).setReadTimeout(3000).setMaxNetworkRetries(0).build();
        var probe=com.stripe.model.PaymentIntent.retrieve("phase5_probe",options);
        if(!"phase5_probe".equals(probe.getId()) || probe.getMetadata()==null || !run.equals(probe.getMetadata().get("run")))
            throw new IllegalStateException("Mock attestation failed");
        com.orderflow.payment.PaymentServiceApplication.main(args);
    }
}
