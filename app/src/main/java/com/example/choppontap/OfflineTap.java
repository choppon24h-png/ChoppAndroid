package com.example.choppontap;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * OfflineTap — Tela exibida quando a TAP está desativada.
 *
 * Funcionalidades:
 *  - Exibe logo Chopp-On, ícone de torneira offline e instrução de próxima TAP.
 *  - Easter egg: 5 cliques no logo → abre AcessoMaster para reativar a TAP.
 *  - Animação pulsante no ícone de torneira.
 *  - Bloqueio do botão Voltar (TAP não pode ser reativada pelo cliente).
 */
public class OfflineTap extends AppCompatActivity {

    private static final String TAG = "OFFLINE_TAP";

    private ImageView logoOffline;
    private ImageView icTapOffline;
    private TextView  txtSecretHint;

    // Easter egg: contador de cliques no logo
    private int    secretClickCount = 0;
    private final Handler handler   = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_tap);

        Log.d(TAG, "OfflineTap iniciada — TAP em modo OFFLINE");

        setupFullscreen();
        setupViews();
        setupAnimations();
        setupEasterEgg();
        blockBackButton();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Configuração de tela cheia (igual ao Home)
    // ─────────────────────────────────────────────────────────────────────────
    private void setupFullscreen() {
        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView());
        wic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Referências de View
    // ─────────────────────────────────────────────────────────────────────────
    private void setupViews() {
        logoOffline    = findViewById(R.id.logoOffline);
        icTapOffline   = findViewById(R.id.icTapOffline);
        txtSecretHint  = findViewById(R.id.txtSecretHint);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animação pulsante no ícone da torneira
    // ─────────────────────────────────────────────────────────────────────────
    private void setupAnimations() {
        try {
            Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse_scale);
            icTapOffline.startAnimation(pulse);
            Log.d(TAG, "Animação pulsante iniciada no ícone da torneira");
        } catch (Exception e) {
            Log.w(TAG, "Não foi possível carregar animação: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Easter Egg: 5 cliques no logo → AcessoMaster → ServiceTools (reativar)
    // ─────────────────────────────────────────────────────────────────────────
    private void setupEasterEgg() {
        logoOffline.setOnClickListener(v -> {
            secretClickCount++;
            Log.d(TAG, "Clique no logo: " + secretClickCount + "/5");

            // Feedback visual sutil (hint invisível ao cliente casual)
            if (secretClickCount >= 2 && secretClickCount < 5) {
                txtSecretHint.setVisibility(android.view.View.VISIBLE);
                txtSecretHint.setText("···".substring(0, Math.min(secretClickCount - 1, 3)));
            }

            if (secretClickCount >= 5) {
                // Acesso liberado!
                secretClickCount = 0;
                txtSecretHint.setVisibility(android.view.View.INVISIBLE);
                txtSecretHint.setText("");

                Log.i(TAG, "Easter egg ativado! Abrindo AcessoMaster...");

                // Pequena animação de feedback antes de abrir
                logoOffline.animate()
                        .scaleX(1.15f).scaleY(1.15f)
                        .setDuration(120)
                        .withEndAction(() -> {
                            logoOffline.animate()
                                    .scaleX(1.0f).scaleY(1.0f)
                                    .setDuration(120)
                                    .withEndAction(() -> {
                                        Intent intent = new Intent(OfflineTap.this, AcessoMaster.class);
                                        // Flag extra para indicar que veio da tela Offline
                                        // (AcessoMaster usará isso para voltar ao Home após reativar)
                                        intent.putExtra("from_offline", true);
                                        startActivity(intent);
                                    })
                                    .start();
                        })
                        .start();
                return;
            }

            // Reset do contador após 2.5 segundos de inatividade
            handler.removeCallbacksAndMessages("secret_reset");
            handler.postAtTime(() -> {
                if (secretClickCount > 0) {
                    Log.d(TAG, "Contador secreto resetado por inatividade");
                    secretClickCount = 0;
                    txtSecretHint.setVisibility(android.view.View.INVISIBLE);
                    txtSecretHint.setText("");
                }
            }, "secret_reset", android.os.SystemClock.uptimeMillis() + 2500);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bloquear o botão Voltar (cliente não pode sair da tela OFFLINE)
    // ─────────────────────────────────────────────────────────────────────────
    private void blockBackButton() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Não faz nada — a tela OFFLINE é um bloqueio intencional
                Log.d(TAG, "Botão Voltar bloqueado na tela OFFLINE");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reinicia a animação se necessário (ex: ao voltar do AcessoMaster sem reativar)
        setupAnimations();
        // Reseta o contador secreto ao voltar para a tela
        secretClickCount = 0;
        if (txtSecretHint != null) {
            txtSecretHint.setVisibility(android.view.View.INVISIBLE);
            txtSecretHint.setText("");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (icTapOffline != null) icTapOffline.clearAnimation();
    }
}
