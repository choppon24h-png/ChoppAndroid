package com.example.choppontap;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ApiHelper - Cliente HTTP para comunicação com backend
 *
 * MELHORIAS IMPLEMENTADAS:
 * ✅ Timeout configurável
 * ✅ Token JWT melhorado
 * ✅ Xdebug removido de produção
 * ✅ Logging detalhado
 * ✅ Tratamento de erro melhorado
 */
public class ApiHelper {
    private static final String TAG = "ApiHelper";

    // ✅ NOVO: OkHttpClient com timeouts configurados
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private final String urlApi = "https://ochoppoficial.com.br/";
    private final String api = urlApi + "api/";

    // ✅ MELHORADO: Chave JWT mais forte (em produção, usar variável de ambiente)
    private final String key = "sua_chave_secreta_forte_aqui_com_mais_caracteres_aleatorios_2026";

    /**
     * ✅ MELHORADO: Gerar token JWT com expiração e claims válidos
     */
    private String gerarToken() {
        try {
            // Expiração: 1 hora
            long expirationTime = System.currentTimeMillis() + (60 * 60 * 1000);

            String token = Jwts.builder()
                    .claim("app", "choppon_tap")
                    .claim("version", "1.0")
                    .claim("timestamp", System.currentTimeMillis())
                    .setIssuedAt(new Date())
                    .setExpiration(new Date(expirationTime))
                    .signWith(SignatureAlgorithm.HS256, key.getBytes())
                    .compact();

            Log.d(TAG, "Token JWT gerado com sucesso");
            return token;

        } catch (Exception e) {
            Log.e(TAG, "Erro ao gerar token JWT", e);
            return "";
        }
    }

    /**
     * ✅ NOVO: Criar corpo da requisição com validação
     */
    private RequestBody createBody(Map<String, String> body) {
        FormBody.Builder builder = new FormBody.Builder();
        if (body != null) {
            for (Map.Entry<String, String> entry : body.entrySet()) {
                String value = entry.getValue() != null ? entry.getValue() : "";
                builder.add(entry.getKey(), value);
                Log.d(TAG, "Parâmetro: " + entry.getKey() + " = " + value);
            }
        }
        return builder.build();
    }

    /**
     * ✅ MELHORADO: Enviar POST com logging detalhado
     *
     * REMOVIDO: XDEBUG_SESSION_START em produção
     * ADICIONADO: Logging de URL, headers, timeout
     */
    public void sendPost(Map<String, String> body, String endpoint, Callback callback) {
        try {
            String token = gerarToken();
            RequestBody formBody = createBody(body);

            // Garantir que o endpoint tenha .php
            String cleanEndpoint = endpoint.endsWith(".php") ? endpoint : endpoint + ".php";
            String fullUrl = api + cleanEndpoint;

            Log.i(TAG, "=== INICIANDO REQUISIÇÃO ===");
            Log.i(TAG, "URL: " + fullUrl);
            Log.i(TAG, "Método: POST");
            Log.i(TAG, "Timeout: 10s (connect), 10s (read), 10s (write)");
            Log.d(TAG, "Token: " + token.substring(0, Math.min(20, token.length())) + "...");

            // ❌ REMOVIDO: XDEBUG_SESSION_START (era adicionado em toda requisição)
            // if (fullUrl.contains("?")) {
            //     fullUrl += "&XDEBUG_SESSION_START=1";
            // } else {
            //     fullUrl += "?XDEBUG_SESSION_START=1";
            // }

            Request request = new Request.Builder()
                    .url(fullUrl)
                    .header("token", token)
                    .header("User-Agent", "ChoppOnTap/1.0")
                    .post(formBody)
                    .build();

            Log.d(TAG, "Enviando requisição...");
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "=== FALHA NA REQUISIÇÃO ===", e);
                    Log.e(TAG, "Mensagem: " + e.getMessage());
                    Log.e(TAG, "Causa: " + (e.getCause() != null ? e.getCause().toString() : "N/A"));
                    Log.e(TAG, "URL: " + call.request().url());

                    // Chamar callback original
                    callback.onFailure(call, e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    Log.i(TAG, "=== RESPOSTA RECEBIDA ===");
                    Log.i(TAG, "Código HTTP: " + response.code());
                    Log.i(TAG, "Mensagem: " + response.message());
                    Log.d(TAG, "Headers: " + response.headers());

                    if (response.isSuccessful()) {
                        Log.i(TAG, "✅ Requisição bem-sucedida");
                    } else {
                        Log.w(TAG, "⚠️ Resposta com erro HTTP: " + response.code());
                    }

                    // Chamar callback original
                    callback.onResponse(call, response);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Erro ao preparar requisição", e);
            // Chamar onFailure do callback
            callback.onFailure(null, new IOException(e));
        }
    }

    /**
     * ✅ NOVO: Enviar POST com retry automático
     */
    public void sendPostWithRetry(Map<String, String> body, String endpoint, Callback callback, int maxAttempts) {
        sendPostWithRetryInternal(body, endpoint, callback, 1, maxAttempts);
    }

    /**
     * ✅ NOVO: Implementação interna de retry
     */
    private void sendPostWithRetryInternal(Map<String, String> body, String endpoint, Callback callback, int attempt, int maxAttempts) {
        sendPost(body, endpoint, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (attempt < maxAttempts) {
                    long delay = (long) Math.pow(2, attempt) * 1000; // 2s, 4s, 8s...
                    Log.w(TAG, "Tentativa " + attempt + " falhou. Retentando em " + delay + "ms...");

                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            () -> sendPostWithRetryInternal(body, endpoint, callback, attempt + 1, maxAttempts),
                            delay
                    );
                } else {
                    Log.e(TAG, "Falha após " + maxAttempts + " tentativas");
                    callback.onFailure(call, e);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.i(TAG, "Sucesso na tentativa " + attempt);
                callback.onResponse(call, response);
            }
        });
    }

    /**
     * Obter imagem da URL
     */
    public Bitmap getImage(Tap object) throws IOException {
        if (object.image == null || object.image.isEmpty()) {
            Log.w(TAG, "URL de imagem vazia");
            return null;
        }

        Log.d(TAG, "Baixando imagem de: " + object.image);
        URL url = new URL(object.image);
        return BitmapFactory.decodeStream(url.openConnection().getInputStream());
    }
}
