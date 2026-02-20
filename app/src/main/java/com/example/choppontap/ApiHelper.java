package com.example.choppontap;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiHelper {
    private static final String TAG = "ApiHelper";

    // ✅ SENIOR FIX: Forçado HTTP/1.1 para evitar StreamResetException em transações de cartão
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .protocols(Collections.singletonList(Protocol.HTTP_1_1)) // Estabiliza conexão com servidores Apache antigos
            .retryOnConnectionFailure(true)
            .addInterceptor(chain -> {
                Request request = chain.request();
                Log.d(TAG, "🚀 [API REQ] " + request.method() + " " + request.url());
                return chain.proceed(request);
            })
            .build();

    private final String api = "https://ochoppoficial.com.br/api/";
    private final String key = "teaste";

    /**
     * ✅ RESTAURADO: Warm-up do servidor para "acordar" o backend (PHP-FPM/MySQL)
     */
    public void warmupServer() {
        Log.d(TAG, "🔥 [Warm-up] Iniciando...");
        Request warmupRequest = new Request.Builder()
            .url(api + "verify_tap.php")
            .get()
            .addHeader("X-Warmup", "true")
            .build();
        
        OkHttpClient warmupClient = client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build();
        
        try (Response response = warmupClient.newCall(warmupRequest).execute()) {
            Log.i(TAG, "✅ [Warm-up] Finalizado com código: " + response.code());
        } catch (Exception e) {
            Log.d(TAG, "⚠️ [Warm-up] Falhou ou excedeu tempo (esperado se servidor estava dormindo)");
        }
    }

    private String gerarToken() {
        try {
            long nowMillis = System.currentTimeMillis();
            return Jwts.builder()
                    .setIssuedAt(new Date(nowMillis))
                    .setExpiration(new Date(nowMillis + 3600000))
                    .setId(java.util.UUID.randomUUID().toString())
                    .claim("app", "choppon_tap")
                    .signWith(SignatureAlgorithm.HS256, key.getBytes("UTF-8"))
                    .compact();
        } catch (Exception e) {
            Log.e(TAG, "Erro JWT", e);
            return "";
        }
    }

    public void sendPost(Map<String, String> body, String endpoint, Callback callback) {
        try {
            String token = gerarToken();
            FormBody.Builder builder = new FormBody.Builder();
            
            // ✅ AUDITORIA DE PARÂMETROS
            StringBuilder logParams = new StringBuilder("\n--- PARÂMETROS ENVIADOS ---\n");
            if (body != null) {
                for (Map.Entry<String, String> entry : body.entrySet()) {
                    String val = entry.getValue() != null ? entry.getValue() : "NULL";
                    builder.add(entry.getKey(), val);
                    logParams.append(entry.getKey()).append(": ").append(val).append("\n");
                }
            }
            logParams.append("---------------------------");
            Log.i(TAG, logParams.toString());

            String fullUrl = api + (endpoint.endsWith(".php") ? endpoint : endpoint + ".php");

            Request request = new Request.Builder()
                    .url(fullUrl)
                    .header("token", token)
                    .post(builder.build())
                    .build();

            client.newCall(request).enqueue(callback);
        } catch (Exception e) {
            Log.e(TAG, "Erro preparado requisição", e);
            callback.onFailure(null, new IOException(e));
        }
    }

    public Bitmap getImage(Tap object) throws IOException {
        if (object.image == null || object.image.isEmpty()) return null;
        Request request = new Request.Builder().url(object.image).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            byte[] imageBytes = response.body().bytes();
            
            // ✅ Regra de compressão mantida para evitar OOM
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2; 
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);
        } catch (Exception e) {
            Log.e(TAG, "Erro imagem: " + e.getMessage());
            return null;
        }
    }
}
