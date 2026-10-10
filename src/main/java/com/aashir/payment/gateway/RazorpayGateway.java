package com.aashir.payment.gateway;

import com.aashir.payment.config.RazorpayProperties;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class RazorpayGateway {
    private final RazorpayClient client;
    private final RazorpayProperties properties;

    public RazorpayGateway(RazorpayClient client,RazorpayProperties properties) {
        this.properties = properties;
        this.client = client;

    }
    public String getKeyId(){
        return properties.keyId();
    }

    public long toPaise(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Payment amount must be greater than zero");
        }

        return amount.multiply(BigDecimal.valueOf(100))
                .longValueExact();
    }

    public Order createOrder(long amountInPaise, String currency, String receipt)
            throws RazorpayException {
        JSONObject request = new JSONObject();
        request.put("amount", amountInPaise);
        request.put("currency", currency);
        request.put("receipt", receipt);

        return client.orders.create(request);
    }


}
