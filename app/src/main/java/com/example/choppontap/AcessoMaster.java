package com.example.choppontap;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AcessoMaster extends AppCompatActivity {
    private Button btnAcessoQrCode, btnAcessoSenha;
    private LinearLayout layoutQrAcesso;
    private TextInputLayout layoutInputSenha;
    private TextInputEditText edtSenhaAcesso;
    private ImageView imgQrAcesso;
    private TextView txtSenhaGerada;
    private String android_id;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_acesso_master);

        android_id = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);

        btnAcessoQrCode = findViewById(R.id.btnAcessoQrCode);
        btnAcessoSenha = findViewById(R.id.btnAcessoSenha);
        layoutQrAcesso = findViewById(R.id.layoutQrAcesso);
        layoutInputSenha = findViewById(R.id.layoutInputSenha);
        edtSenhaAcesso = findViewById(R.id.edtSenhaAcesso);
        imgQrAcesso = findViewById(R.id.imgQrAcesso);
        txtSenhaGerada = findViewById(R.id.txtSenhaGerada);

        btnAcessoQrCode.setOnClickListener(v -> gerarAcessoQrCode());
        btnAcessoSenha.setOnClickListener(v -> mostrarInputSenha());

        edtSenhaAcesso.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() == 6) {
                    String senha = s.toString();
                    // ✅ REGRA SENHA PADRÃO: 259087
                    if (senha.equals("259087")) {
                        liberarAcesso();
                    } else {
                        validarSenhaAPI(senha);
                    }
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    private void gerarAcessoQrCode() {
        layoutQrAcesso.setVisibility(View.VISIBLE);
        layoutInputSenha.setVisibility(View.GONE);
        
        String caracteres = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder senha = new StringBuilder();
        Random rnd = new Random();
        while (senha.length() < 6) {
            senha.append(caracteres.charAt(rnd.nextInt(caracteres.length())));
        }
        
        final String senhaFinal = senha.toString();
        txtSenhaGerada.setText(senhaFinal);

        // ✅ GERAÇÃO REAL DO QR CODE (Via API de Gráficos para evitar dependências pesadas)
        executor.execute(() -> {
            try {
                URL url = new URL("https://api.qrserver.com/v1/create-qr-code/?size=250x250&data=" + senhaFinal);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setDoInput(true);
                connection.connect();
                InputStream input = connection.getInputStream();
                Bitmap myBitmap = BitmapFactory.decodeStream(input);
                
                runOnUiThread(() -> {
                    if (myBitmap != null) {
                        imgQrAcesso.setImageBitmap(myBitmap);
                    }
                });
            } catch (Exception e) {
                Log.e("ACESSO_MASTER", "Erro ao baixar QR Code: " + e.getMessage());
            }
        });
    }

    private void mostrarInputSenha() {
        layoutInputSenha.setVisibility(View.VISIBLE);
        layoutQrAcesso.setVisibility(View.GONE);
        edtSenhaAcesso.requestFocus();
    }

    private void validarSenhaAPI(String senha) {
        // Chamada de API para validação futura/remota
        Toast.makeText(this, "Validando na nuvem...", Toast.LENGTH_SHORT).show();
        // (Aqui viria o seu sendPost para validate_master.php se não for a senha padrão)
    }

    private void liberarAcesso() {
        Toast.makeText(this, "Acesso Master Liberado", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(AcessoMaster.this, ServiceTools.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
