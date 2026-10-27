package com.aashir.payment.gateway;

import com.aashir.payment.config.RazorpayProperties;
import com.razorpay.Order;
import com.razorpay.Payment;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

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

    public boolean verifyPaymentSignature(
            String razorpayOrderId,
            String razorpayPaymentId,
            String razorpaySignature
    ){
        if(razorpayOrderId == null || razorpayOrderId.isBlank()
                || razorpayPaymentId == null || razorpayPaymentId.isBlank()
        || razorpaySignature == null || !razorpaySignature.matches("[0-9a-fA-F]{64}") ){
            return false;
        }
        try {
            String message = razorpayOrderId + "|" + razorpayPaymentId;

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    properties.keySecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"
            );

            mac.init(secretKey);
            byte[] expectedSignature = mac.doFinal(
                    message.getBytes(StandardCharsets.UTF_8)
            );

            byte[] actualSignature = HexFormat.of().parseHex(razorpaySignature);

            return MessageDigest.isEqual(expectedSignature, actualSignature);
        }catch (java.security.GeneralSecurityException exception){
            throw new IllegalStateException(
                    "Unable to verify Razorpay payment signature", exception
            );
        }
    }

    public com.razorpay.Payment fetchPayment(String razorpayPaymentId) throws RazorpayException {
        return client.payments.fetch(razorpayPaymentId);
    }

    public List<Payment> fetchOrderPayments(String razorpayOrderId)
            throws RazorpayException {
        return client.orders.fetchPayments(razorpayOrderId);
    }

}
