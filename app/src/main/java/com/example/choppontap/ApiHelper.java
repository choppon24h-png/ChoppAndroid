package com.example.choppontap;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.example.choppontap.BuildConfig;

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

    // ✅ CORRIGIDO: OkHttpClient com EventListener e interceptor robusto
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // ✅ NOVO: EventListener para monitorar todas as etapas da conexão
            .eventListener(new okhttp3.EventListener() {
                @Override
                public void callStart(okhttp3.Call call) {
                    Log.d(TAG, "[Event] callStart: " + call.request().url());
                }
                
                @Override
                public void dnsStart(okhttp3.Call call, String domainName) {
                    Log.d(TAG, "[Event] dnsStart: " + domainName);
                }
                
                @Override
                public void dnsEnd(okhttp3.Call call, String domainName, java.util.List<java.net.InetAddress> inetAddressList) {
                    Log.d(TAG, "[Event] dnsEnd: " + domainName + " -> " + inetAddressList.size() + " IPs");
                }
                
                @Override
                public void connectStart(okhttp3.Call call, java.net.InetSocketAddress inetSocketAddress, java.net.Proxy proxy) {
                    Log.d(TAG, "[Event] connectStart: " + inetSocketAddress);
                }
                
                @Override
                public void secureConnectStart(okhttp3.Call call) {
                    Log.d(TAG, "[Event] secureConnectStart (SSL/TLS)");
                }
                
                @Override
                public void secureConnectEnd(okhttp3.Call call, okhttp3.Handshake handshake) {
                    Log.d(TAG, "[Event] secureConnectEnd: " + (handshake != null ? handshake.tlsVersion() : "null"));
                }
                
                @Override
                public void connectEnd(okhttp3.Call call, java.net.InetSocketAddress inetSocketAddress, java.net.Proxy proxy, okhttp3.Protocol protocol) {
                    Log.d(TAG, "[Event] connectEnd: " + protocol);
                }
                
                @Override
                public void connectFailed(okhttp3.Call call, java.net.InetSocketAddress inetSocketAddress, java.net.Proxy proxy, okhttp3.Protocol protocol, IOException ioe) {
                    Log.e(TAG, "[Event] connectFailed: " + inetSocketAddress, ioe);
                }
                
                @Override
                public void connectionAcquired(okhttp3.Call call, okhttp3.Connection connection) {
                    Log.d(TAG, "[Event] connectionAcquired");
                }
                
                @Override
                public void requestHeadersStart(okhttp3.Call call) {
                    Log.d(TAG, "[Event] requestHeadersStart");
                }
                
                @Override
                public void requestHeadersEnd(okhttp3.Call call, okhttp3.Request request) {
                    Log.d(TAG, "[Event] requestHeadersEnd");
                }
                
                @Override
                public void requestBodyStart(okhttp3.Call call) {
                    Log.d(TAG, "[Event] requestBodyStart");
                }
                
                @Override
                public void requestBodyEnd(okhttp3.Call call, long byteCount) {
                    Log.d(TAG, "[Event] requestBodyEnd: " + byteCount + " bytes");
                }
                
                @Override
                public void responseHeadersStart(okhttp3.Call call) {
                    Log.d(TAG, "[Event] responseHeadersStart ✅ SERVIDOR RESPONDEU!");
                }
                
                @Override
                public void responseHeadersEnd(okhttp3.Call call, okhttp3.Response response) {
                    Log.i(TAG, "[Event] responseHeadersEnd: HTTP " + response.code() + " " + response.message());
                }
                
                @Override
                public void responseBodyStart(okhttp3.Call call) {
                    Log.d(TAG, "[Event] responseBodyStart");
                }
                
                @Override
                public void responseBodyEnd(okhttp3.Call call, long byteCount) {
                    Log.d(TAG, "[Event] responseBodyEnd: " + byteCount + " bytes");
                }
                
                @Override
                public void callEnd(okhttp3.Call call) {
                    Log.i(TAG, "[Event] callEnd ✅ SUCESSO");
                }
                
                @Override
                public void callFailed(okhttp3.Call call, IOException ioe) {
                    Log.e(TAG, "[Event] callFailed ❌ ERRO", ioe);
                }
                
                @Override
                public void canceled(okhttp3.Call call) {
                    Log.w(TAG, "[Event] canceled");
                }
            })
            // ✅ MELHORADO: Interceptor com try-catch robusto
            .addInterceptor(chain -> {
                okhttp3.Request request = chain.request();
                Log.d(TAG, "[Interceptor] Enviando: " + request.url());
                long startTime = System.currentTimeMillis();
                
                try {
                    Log.d(TAG, "[Interceptor] Chamando chain.proceed()...");
                    okhttp3.Response response = chain.proceed(request);
                    
                    long duration = System.currentTimeMillis() - startTime;
                    Log.i(TAG, "[Interceptor] ✅ Recebido em " + duration + "ms: HTTP " + response.code());
                    
                    return response;
                    
                } catch (java.net.SocketTimeoutException e) {
                    long duration = System.currentTimeMillis() - startTime;
                    Log.e(TAG, "[Interceptor] ❌ SocketTimeoutException após " + duration + "ms", e);
                    throw e;
                    
                } catch (java.net.UnknownHostException e) {
                    long duration = System.currentTimeMillis() - startTime;
                    Log.e(TAG, "[Interceptor] ❌ UnknownHostException após " + duration + "ms", e);
                    throw e;
                    
                } catch (java.net.ConnectException e) {
                    long duration = System.currentTimeMillis() - startTime;
                    Log.e(TAG, "[Interceptor] ❌ ConnectException após " + duration + "ms", e);
                    throw e;
                    
                } catch (IOException e) {
                    long duration = System.currentTimeMillis() - startTime;
                    Log.e(TAG, "[Interceptor] ❌ IOException após " + duration + "ms", e);
                    Log.e(TAG, "[Interceptor] Tipo: " + e.getClass().getSimpleName());
                    Log.e(TAG, "[Interceptor] Mensagem: " + e.getMessage());
                    throw e;
                    
                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - startTime;
                    Log.e(TAG, "[Interceptor] ❌ Exception inesperada após " + duration + "ms", e);
                    throw new IOException("Erro inesperado no interceptor", e);
                }
            })
            .build();

    private final String urlApi = "https://ochoppoficial.com.br/";
    private final String api = urlApi + "api/";

    // ✅ CORRIGIDO: Chave JWT sincronizada com backend PHP
    // IMPORTANTE: Esta chave DEVE ser a mesma definida em config.php (JWT_SECRET)
    private final String key = "teaste";

    /**
     * ✅ CORRIGIDO: Gerar token JWT compatível com backend PHP
     * 
     * Backend PHP espera:
     * - Claims: iat (issued at), exp (expiration), jti (JWT ID)
     * - Algoritmo: HS256
     * - Chave: "teaste" (UTF-8)
     */
    private String gerarToken() {
        try {
            // Expiração: 1 hora (3600 segundos)
            long nowMillis = System.currentTimeMillis();
            Date now = new Date(nowMillis);
            long expirationMillis = nowMillis + (60 * 60 * 1000);
            Date expiration = new Date(expirationMillis);

            // Gerar JTI (JWT ID) único
            String jti = java.util.UUID.randomUUID().toString().replace("-", "");

            // Construir token com claims compatíveis com PHP
            String token = Jwts.builder()
                    .setIssuedAt(now)                    // iat
                    .setExpiration(expiration)           // exp
                    .setId(jti)                          // jti
                    .claim("app", "choppon_tap")         // claim customizado
                    .claim("version", "1.0")             // claim customizado
                    .signWith(
                        SignatureAlgorithm.HS256,
                        key.getBytes(java.nio.charset.StandardCharsets.UTF_8)  // ✅ UTF-8 explícito
                    )
                    .compact();

            Log.d(TAG, "Token JWT gerado com sucesso");
            Log.d(TAG, "JTI: " + jti);
            Log.d(TAG, "Issued At: " + now.getTime() / 1000 + " (" + now + ")");
            Log.d(TAG, "Expiration: " + expiration.getTime() / 1000 + " (" + expiration + ")");
            Log.d(TAG, "Token (primeiros 50 chars): " + token.substring(0, Math.min(50, token.length())) + "...");
            
            // ✅ NOVO: Log do token completo para debug (remover em produção)
            if (BuildConfig.DEBUG) {
                Log.v(TAG, "Token completo: " + token);
            }

            return token;

        } catch (Exception e) {
            Log.e(TAG, "Erro ao gerar token JWT", e);
            Log.e(TAG, "Stack trace completo:", e);
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
            Log.d(TAG, "Thread atual: " + Thread.currentThread().getName());
            
            Call call = client.newCall(request);
            Log.d(TAG, "Call criado: " + call);
            
            call.enqueue(new Callback() {
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
                    Log.i(TAG, "Thread: " + Thread.currentThread().getName());
                    Log.i(TAG, "Código HTTP: " + response.code());
                    Log.i(TAG, "Mensagem: " + response.message());
                    Log.i(TAG, "Protocolo: " + response.protocol());
                    Log.d(TAG, "Headers: " + response.headers());
                    
                    // ✅ NOVO: Log do corpo da resposta
                    String responseBodyString = null;
                    if (response.body() != null) {
                        try {
                            responseBodyString = response.body().string();
                            Log.d(TAG, "Corpo da resposta (" + responseBodyString.length() + " chars): " + 
                                (responseBodyString.length() > 500 ? responseBodyString.substring(0, 500) + "..." : responseBodyString));
                            
                            // ✅ IMPORTANTE: Recriar o ResponseBody porque já foi consumido
                            response = response.newBuilder()
                                .body(okhttp3.ResponseBody.create(response.body().contentType(), responseBodyString))
                                .build();
                        } catch (Exception e) {
                            Log.e(TAG, "Erro ao ler corpo da resposta", e);
                        }
                    } else {
                        Log.w(TAG, "Corpo da resposta é NULL");
                    }

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
