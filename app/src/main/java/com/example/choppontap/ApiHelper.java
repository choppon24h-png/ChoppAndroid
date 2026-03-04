package com.example.choppontap;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/**
 * ApiHelper — cliente HTTP centralizado para o app ChoppOn Tap.
 *
 * CORREÇÕES v2.1 (fix: timeout e SocketException no IMEI_CHECK):
 *
 * CAUSA RAIZ IDENTIFICADA NO LOG:
 *   - Tentativa 1 (22:24:32): timeout após ~62s → SocketTimeoutException
 *   - Tentativa 2 (22:25:35): timeout após ~90s → InterruptedIOException
 *   - Causa: servidor ochoppoficial.com.br usa PHP compartilhado (cold start lento)
 *     e o handshake TLS falha quando o socket é fechado pelo servidor antes do
 *     cliente terminar de ler os headers HTTP.
 *
 * SOLUÇÕES APLICADAS:
 *   1. OkHttpClient SINGLETON — evita criar novo cliente (e novo connection pool)
 *      a cada instância de ApiHelper. Antes, cada new ApiHelper() criava um cliente
 *      separado, desperdiçando conexões e aumentando latência.
 *
 *   2. ConnectionPool configurado — mantém conexões abertas por 5 min,
 *      reutilizando o handshake TLS já estabelecido nas tentativas seguintes.
 *
 *   3. Timeouts reduzidos — connectTimeout de 30s → 15s, readTimeout de 60s → 20s,
 *      callTimeout de 90s → 45s. Falha mais rápido e retenta antes do servidor
 *      fechar o socket por inatividade.
 *
 *   4. retryOnConnectionFailure(true) — já estava, mantido.
 *
 *   5. Suporte a TLSv1.2 e TLSv1.3 explícito — evita o "Socket closed" durante
 *      o handshake SSL que ocorre quando o servidor não negocia o protocolo correto.
 *
 *   6. warmupServer() melhorado — agora usa o cliente singleton (pool compartilhado)
 *      para que a conexão aquecida seja reaproveitada pela requisição principal.
 */
public class ApiHelper {
    private static final String TAG = "ApiHelper";

    // ── Singleton do OkHttpClient ─────────────────────────────────────────────
    // Compartilhar uma única instância garante reuso do connection pool e do
    // cache TLS entre todas as chamadas do app, reduzindo drasticamente a
    // latência em servidores com cold start lento (PHP compartilhado).
    private static volatile OkHttpClient sClient;

    private static OkHttpClient getClient() {
        if (sClient == null) {
            synchronized (ApiHelper.class) {
                if (sClient == null) {
                    sClient = buildClient();
                }
            }
        }
        return sClient;
    }

    private static OkHttpClient buildClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                // Timeouts reduzidos: falha mais rápido e retenta antes do servidor
                // fechar o socket por inatividade (evita o "Socket closed" no TLS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                // Pool de conexões: mantém até 5 conexões abertas por 5 minutos.
                // Após o warm-up, a conexão TLS já estabelecida é reaproveitada.
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                // Retry automático em falhas de conexão (ex: Connection reset)
                .retryOnConnectionFailure(true)
                // HTTP/1.1 apenas — evita problemas de multiplexação HTTP/2 em
                // servidores PHP compartilhados que não suportam h2 corretamente
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    Log.d(TAG, "🚀 [API REQ] " + request.method() + " " + request.url());
                    return chain.proceed(request);
                });

        // Configura TLS explicitamente para forçar TLSv1.2/TLSv1.3 e evitar
        // o "Socket closed" que ocorre quando o servidor rejeita versões antigas
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
            SSLSocketFactory baseFactory = sslContext.getSocketFactory();

            // Wrapper que força TLSv1.2 e TLSv1.3 em todos os sockets
            SSLSocketFactory tlsFactory = new TlsSocketFactory(baseFactory);

            // TrustManager padrão do sistema (valida certificados normalmente)
            X509TrustManager trustManager = getSystemTrustManager();
            if (trustManager != null) {
                builder.sslSocketFactory(tlsFactory, trustManager);
            }
        } catch (Exception e) {
            Log.w(TAG, "Não foi possível configurar TLS customizado: " + e.getMessage());
        }

        return builder.build();
    }

    /**
     * Obtém o TrustManager padrão do sistema Android.
     * Retorna null se não for possível obtê-lo (o OkHttp usará o padrão).
     */
    private static X509TrustManager getSystemTrustManager() {
        try {
            javax.net.ssl.TrustManagerFactory tmf =
                    javax.net.ssl.TrustManagerFactory.getInstance(
                            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Não foi possível obter TrustManager do sistema: " + e.getMessage());
        }
        return null;
    }

    // ── Configuração da API ───────────────────────────────────────────────────
    private final String api = "https://ochoppoficial.com.br/api/";
    private final String key = "teaste";

    // ─────────────────────────────────────────────────────────────────────────
    // Warm-up do servidor
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Aquece o servidor com uma requisição GET leve antes da requisição principal.
     *
     * IMPORTANTE: agora usa o cliente SINGLETON para que a conexão TLS estabelecida
     * aqui seja reaproveitada pelo sendPost() logo em seguida (via connection pool).
     * Antes, cada new ApiHelper() criava um cliente separado e o warm-up não
     * beneficiava a requisição principal.
     */
    public void warmupServer() {
        Log.d(TAG, "🔥 [Warm-up] Iniciando com cliente singleton...");
        Request warmupRequest = new Request.Builder()
                .url(api + "verify_tap.php")
                .get()
                .addHeader("X-Warmup", "true")
                .build();

        // Usa o cliente singleton com timeout curto para o warm-up
        OkHttpClient warmupClient = getClient().newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .build();

        try (Response response = warmupClient.newCall(warmupRequest).execute()) {
            Log.i(TAG, "✅ [Warm-up] Finalizado com código: " + response.code());
        } catch (Exception e) {
            Log.d(TAG, "⚠️ [Warm-up] Falhou ou excedeu tempo: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Geração de Token JWT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Gera token JWT com margem de segurança para clock skew.
     * Inicia 5 minutos no passado para evitar erros de relógio dessincronizado.
     */
    private String gerarToken() {
        try {
            long nowMillis = System.currentTimeMillis();
            Date issuedAt  = new Date(nowMillis - 300000);  // -5 min (clock skew)
            Date expiresAt = new Date(nowMillis + 7200000); // +2 horas

            return Jwts.builder()
                    .setIssuedAt(issuedAt)
                    .setExpiration(expiresAt)
                    .setId(java.util.UUID.randomUUID().toString())
                    .claim("app", "choppon_tap")
                    .signWith(SignatureAlgorithm.HS256, key.getBytes("UTF-8"))
                    .compact();
        } catch (Exception e) {
            Log.e(TAG, "Erro JWT: " + e.getMessage());
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Requisições HTTP
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Envia uma requisição POST assíncrona para o endpoint especificado.
     * Usa o cliente singleton para aproveitar o connection pool e o cache TLS.
     */
    public void sendPost(Map<String, String> body, String endpoint, Callback callback) {
        try {
            String token = gerarToken();
            FormBody.Builder builder = new FormBody.Builder();

            if (body != null) {
                for (Map.Entry<String, String> entry : body.entrySet()) {
                    builder.add(entry.getKey(),
                            entry.getValue() != null ? entry.getValue() : "NULL");
                }
            }

            String fullUrl = api + (endpoint.endsWith(".php") ? endpoint : endpoint + ".php");

            Request request = new Request.Builder()
                    .url(fullUrl)
                    .header("token", token)
                    .post(builder.build())
                    .build();

            getClient().newCall(request).enqueue(callback);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao preparar requisição: " + e.getMessage());
            callback.onFailure(null, new IOException(e));
        }
    }

    /**
     * Baixa a imagem da bebida de forma síncrona (deve ser chamado em background thread).
     */
    public Bitmap getImage(Tap object) throws IOException {
        if (object.image == null || object.image.isEmpty()) return null;
        Request request = new Request.Builder().url(object.image).build();
        try (Response response = getClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            byte[] imageBytes = response.body().bytes();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao baixar imagem: " + e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TlsSocketFactory — força TLSv1.2 e TLSv1.3
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * SSLSocketFactory customizado que força TLSv1.2 e TLSv1.3 em todos os sockets.
     *
     * PROBLEMA: O "Socket closed" durante o handshake TLS ocorre quando o servidor
     * rejeita versões antigas (TLSv1.0/1.1) e fecha a conexão. Ao forçar apenas
     * TLSv1.2+, o handshake é negociado corretamente na primeira tentativa.
     */
    private static class TlsSocketFactory extends SSLSocketFactory {
        private static final String[] TLS_PROTOCOLS = { "TLSv1.2", "TLSv1.3" };
        private final SSLSocketFactory delegate;

        TlsSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws IOException {
            return enableTls(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return enableTls(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            return enableTls(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return enableTls(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port,
                                   InetAddress localAddress, int localPort) throws IOException {
            return enableTls(delegate.createSocket(address, port, localAddress, localPort));
        }

        private Socket enableTls(Socket socket) {
            if (socket instanceof SSLSocket) {
                ((SSLSocket) socket).setEnabledProtocols(TLS_PROTOCOLS);
            }
            return socket;
        }
    }
}
