package com.example.choppontap;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.IOException;
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
 */
public class ApiHelper {
    private static final String TAG = "ApiHelper";

    // ✅ MELHORADO: Timeouts aumentados para 30s/60s devido a arquivos grandes (>1MB)
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(chain -> {
                okhttp3.Request request = chain.request();
                Log.d(TAG, "[Interceptor] Enviando: " + request.url());
                return chain.proceed(request);
            })
            .build();

    private final String urlApi = "https://ochoppoficial.com.br/";
    private final String api = urlApi + "api/";
    private final String key = "teaste";

    /**
     * ✅ NOVO: Warm-up do servidor
     * 
     * Faz uma requisição rápida para "acordar" o servidor antes da requisição real.
     * Ignora o resultado (sucesso ou falha).
     * 
     * BENEFÍCIO: Reduz chance de Connection reset na primeira tentativa real.
     * ANÁLISE: Tentativa 1 falha em ~4s (Connection reset), tentativa 3 funciona.
     *          Warm-up prepara o servidor (PHP-FPM, MySQL, SSL/TLS).
     */
    public void warmupServer() {
        Log.d(TAG, "🔥 [Warm-up] Iniciando...");
        
        Request warmupRequest = new Request.Builder()
            .url(api + "verify_tap.php")  // Mesmo endpoint
            .get()  // GET é mais leve que POST
            .addHeader("X-Warmup", "true")  // Identificar como warm-up
            .build();
        
        // Cliente com timeout muito curto (2-3s)
        OkHttpClient warmupClient = client.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build();
        
        try {
            long startTime = System.currentTimeMillis();
            Response response = warmupClient.newCall(warmupRequest).execute();
            long duration = System.currentTimeMillis() - startTime;
            
            Log.i(TAG, "✅ [Warm-up] Servidor respondeu em " + duration + "ms (código " + response.code() + ")");
            response.close();
            
        } catch (IOException e) {
            Log.d(TAG, "⚠️ [Warm-up] Falhou: " + e.getMessage() + " (esperado, servidor foi acordado)");
        }
        
        // Aguardar 1 segundo para servidor se estabilizar
        try {
            Thread.sleep(1000);
            Log.d(TAG, "[Warm-up] Aguardou 1s para estabilização");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "[Warm-up] Interrompido durante sleep");
        }
    }

    /**
     * ✅ NOVO: Retorna timeout baseado no número da tentativa
     * 
     * ANÁLISE:
     * - Tentativa 1: Falha rápido (Connection reset em ~4s) → timeout 10s
     * - Tentativa 2: Falha lento (Timeout em ~65s) → timeout 20s
     * - Tentativa 3: Sucesso (Responde em ~10s) → timeout 30s
     */
    private int getConnectTimeout(int attemptNumber) {
        switch (attemptNumber) {
            case 1: return 10;  // 10s - Rápido
            case 2: return 20;  // 20s - Médio
            case 3: return 30;  // 30s - Longo
            default: return 15; // Fallback
        }
    }

    private int getReadTimeout(int attemptNumber) {
        switch (attemptNumber) {
            case 1: return 10;  // 10s
            case 2: return 20;  // 20s
            case 3: return 30;  // 30s
            default: return 15;
        }
    }

    private int getCallTimeout(int attemptNumber) {
        // Call timeout = connect + read + margem
        return getConnectTimeout(attemptNumber) + getReadTimeout(attemptNumber) + 5;
    }

    /**
     * ✅ NOVO: Cria cliente OkHttp com timeout específico para a tentativa
     */
    public OkHttpClient getClientWithTimeout(int attemptNumber) {
        int connectTimeout = getConnectTimeout(attemptNumber);
        int readTimeout = getReadTimeout(attemptNumber);
        int callTimeout = getCallTimeout(attemptNumber);
        
        Log.d(TAG, String.format("[Timeout] Tentativa %d: connect=%ds, read=%ds, call=%ds",
            attemptNumber, connectTimeout, readTimeout, callTimeout));
        
        return client.newBuilder()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .callTimeout(callTimeout, TimeUnit.SECONDS)
            .build();
    }

    private String gerarToken() {
        try {
            long nowMillis = System.currentTimeMillis();
            Date now = new Date(nowMillis);
            long expirationMillis = nowMillis + (60 * 60 * 1000);

            return Jwts.builder()
                    .setIssuedAt(now)
                    .setExpiration(new Date(expirationMillis))
                    .setId(java.util.UUID.randomUUID().toString())
                    .claim("app", "choppon_tap")
                    .signWith(SignatureAlgorithm.HS256, key.getBytes("UTF-8"))
                    .compact();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao gerar JWT", e);
            return "";
        }
    }

    /**
     * ✅ MANTIDO: Método original para compatibilidade
     */
    public void sendPost(Map<String, String> body, String endpoint, Callback callback) {
        sendPost(body, endpoint, callback, 1);  // Usa tentativa 1 como padrão
    }

    /**
     * ✅ NOVO: Método com attemptNumber para timeout progressivo
     */
    public void sendPost(Map<String, String> body, String endpoint, Callback callback, int attemptNumber) {
        try {
            String token = gerarToken();
            FormBody.Builder builder = new FormBody.Builder();
            if (body != null) {
                for (Map.Entry<String, String> entry : body.entrySet()) {
                    builder.add(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
                }
            }

            String fullUrl = api + (endpoint.endsWith(".php") ? endpoint : endpoint + ".php");

            Request request = new Request.Builder()
                    .url(fullUrl)
                    .header("token", token)
                    .post(builder.build())
                    .build();

            // ✅ MELHORADO: Usa cliente com timeout específico para esta tentativa
            OkHttpClient clientWithTimeout = getClientWithTimeout(attemptNumber);
            clientWithTimeout.newCall(request).enqueue(callback);
        } catch (Exception e) {
            Log.e(TAG, "Erro preparado requisição", e);
            callback.onFailure(null, new IOException(e));
        }
    }

    /**
     * ✅ NOVO: Obter imagem com regra de compressão e timeout estendido
     */
    public Bitmap getImage(Tap object) throws IOException {
        if (object.image == null || object.image.isEmpty()) {
            Log.w(TAG, "URL de imagem vazia");
            return null;
        }

        Log.d(TAG, "Iniciando download de imagem pesada (>1MB): " + object.image);

        Request request = new Request.Builder()
                .url(object.image)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.e(TAG, "Falha ao baixar imagem. Código: " + response.code());
                return null;
            }

            byte[] imageBytes = response.body().bytes();
            Log.d(TAG, "Bytes recebidos: " + imageBytes.length);

            // ✅ REGRA DE COMPRESSÃO: Decodifica apenas o tamanho primeiro
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);

            // Calcula o fator de escala (reduzir se for muito grande para a tela)
            options.inSampleSize = calculateInSampleSize(options, 800, 800);
            options.inJustDecodeBounds = false;
            
            // Decodifica com compressão de memória (inSampleSize)
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);
            
            if (bitmap != null) {
                Log.i(TAG, "Imagem carregada e comprimida. Tamanho final: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            }
            
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Timeout ou erro ao baixar imagem", e);
            return null; // Fallback será tratado na UI
        }
    }

    /**
     * Auxiliar para calcular compressão proporcional
     */
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}
