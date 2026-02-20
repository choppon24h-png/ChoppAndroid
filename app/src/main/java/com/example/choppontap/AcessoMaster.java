package com.example.choppontap;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Base64;
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
import com.google.gson.Gson;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class AcessoMaster extends AppCompatActivity {
    private Button btnAcessoQrCode, btnAcessoSenha;
    private LinearLayout layoutQrAcesso;
    private TextInputLayout layoutInputSenha;
    private TextInputEditText edtSenhaAcesso;
    private ImageView imgQrAcesso;
    private TextView txtSenhaGerada;
    private String android_id;

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
                if (s.length() == 6) validarSenhaMaster(s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    private void gerarAcessoQrCode() {
        layoutQrAcesso.setVisibility(View.VISIBLE);
        layoutInputSenha.setVisibility(View.GONE);
        
        // Gerar senha alfanumérica de 6 dígitos
        String caracteres = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder senha = new StringBuilder();
        Random rnd = new Random();
        while (senha.length() < 6) {
            senha.append(caracteres.charAt(rnd.nextInt(caracteres.length())));
        }
        
        txtSenhaGerada.setText(senha.toString());
        // Implementação futura: Chamar API para gerar QR Code real com esta senha
        Toast.makeText(this, "QR Code gerado para validação futura", Toast.LENGTH_SHORT).show();
    }

    private void mostrarInputSenha() {
        layoutInputSenha.setVisibility(View.VISIBLE);
        layoutQrAcesso.setVisibility(View.GONE);
        edtSenhaAcesso.requestFocus();
    }

    private void validarSenhaMaster(String senha) {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("senha", senha);

        // API fictícia ou endpoint para validar os 6 últimos números do token_sumup
        new ApiHelper().sendPost(body, "validate_master.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(AcessoMaster.this, "Erro de conexão", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    if (response.isSuccessful()) {
                        runOnUiThread(() -> {
                            startActivity(new Intent(AcessoMaster.this, ServiceTools.class));
                            finish();
                        });
                    } else {
                        runOnUiThread(() -> {
                            Toast.makeText(AcessoMaster.this, "Senha Incorreta", Toast.LENGTH_SHORT).show();
                            edtSenhaAcesso.setText("");
                        });
                    }
                }
            }
        });
    }
}
