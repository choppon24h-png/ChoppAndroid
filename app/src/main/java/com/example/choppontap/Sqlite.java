package com.example.choppontap;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

public class Sqlite  extends SQLiteOpenHelper {
    public static final String DB_NAME = "app.sqlite";
    SQLiteDatabase db;
    public  Sqlite(@Nullable Context context) {
        super(context,DB_NAME,null,1);
        db = getWritableDatabase();
    }
    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE tapImage ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "file_name TEXT,"
                + "image_data BLOB,"
                + "ativo BOOLEAN  )";
        db.execSQL(createTable);
        String createTableCartao = "CREATE TABLE tapCartao ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "ativo BOOLEAN  )";
        db.execSQL(createTableCartao);
        String createTableLogs = "CREATE TABLE logs ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "tipo_log VARCHAR(45) NOT NULL, "
                + "log TEXT NOT NULL, "
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                + "enviado BOOLEAN NOT NULL )";
        db.execSQL(createTableLogs);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    }
    public boolean insert(String table, String imagePath, Bitmap bmp) {
        db = getWritableDatabase();

        try {
            // Redimensionar imagem se muito grande (evita SQLiteBlobTooBigException)
            int maxWidth = 400;
            int maxHeight = 400;

            Log.d("Sqlite_Insert", "Tamanho original: " + bmp.getWidth() + "x" + bmp.getHeight());

            if (bmp.getWidth() > maxWidth || bmp.getHeight() > maxHeight) {
                float scale = Math.min(
                        (float) maxWidth / bmp.getWidth(),
                        (float) maxHeight / bmp.getHeight()
                );
                int newWidth = (int) (bmp.getWidth() * scale);
                int newHeight = (int) (bmp.getHeight() * scale);
                bmp = Bitmap.createScaledBitmap(bmp, newWidth, newHeight, true);
                Log.d("Sqlite_Insert", "Redimensionada para: " + newWidth + "x" + newHeight);
            }

            // Comprimir como JPEG com 80% de qualidade (reduz ~90% do tamanho)
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, stream);
            byte[] imageData = stream.toByteArray();

            Log.d("Sqlite_Insert", "Tamanho do blob: " + imageData.length + " bytes");

            ContentValues value = new ContentValues();
            value.put("file_name", imagePath);
            value.put("image_data", imageData);
            value.put("ativo", true);

            Boolean insertResult = db.insert(table, null, value) > 0;

            String whereClauseDifferent = "file_name <> ?";
            String[] whereArgs = {String.valueOf(imagePath)};
            ContentValues valueFalse = new ContentValues();
            valueFalse.put("ativo", false);

            Integer count = db.update(table, valueFalse, whereClauseDifferent, whereArgs);

            db.close();

            Log.d("Sqlite_Insert", "Inserção bem-sucedida!");
            return insertResult;

        } catch (Exception e) {
            Log.e("Sqlite_Insert", "Erro ao inserir imagem: " + e.getMessage(), e);
            db.close();
            return false;
        }
    }
    public boolean insertLog(String tipoLog, String log){
        db = getWritableDatabase();

        ContentValues value =  new ContentValues();
        value.put("tipo_log",tipoLog);
        value.put("log",log);
        value.put("enviado",false);
        Boolean insertResult =db.insert("logs",null,value) > 0;
        db.close();
        return insertResult;
    }
    public boolean updateLog(Integer logId){
        Boolean result = false;
        db = getWritableDatabase();

        ContentValues value =  new ContentValues();
        value.put("enviado",true);

        String whereClause = "id = ?";
        String[] whereArgs = {String.valueOf(logId)};
        Integer count = db.update("logs",value,whereClause,whereArgs);
        if(count > 0){
            result = true;
        }
        db.close();
        return result;
    }
    public ArrayList<Logs> getLogs(){
        db = getWritableDatabase();
        Cursor cursor = db.query("logs", new String[]{"id","tipo_log","log","created_at"},
                "enviado = ?", new String[]{"0"},
                null, null, null);
        String ativoBanco = null;
        ArrayList<Logs> dataList = new ArrayList<>();
        if (cursor != null && cursor.moveToFirst()) {
            do {
                Integer id = cursor.getInt(cursor.getColumnIndex("id"));
                String tipo_log = cursor.getString(cursor.getColumnIndex("tipo_log"));
                String log = cursor.getString(cursor.getColumnIndex("log"));
                String createdAt = cursor.getString(cursor.getColumnIndex("created_at"));

                // Create an object (e.g., a POJO) with the extracted data
                Logs logObj = new Logs(id,tipo_log,log,createdAt,false);
                dataList.add(logObj);

            } while (cursor.moveToNext());
        }
        if(dataList.size() > 0)
        {
            return dataList;
        }
        return null;
    }
    public boolean update(String table,String imagePath, Boolean ativo) {

        db = getWritableDatabase();
        String whereClause = "file_name = ?";
        String whereClauseDifferent = "file_name <> ?";
        String[] whereArgs = {String.valueOf(imagePath)};

        ContentValues value =  new ContentValues();
        value.put("ativo",ativo);
        ContentValues valueFalse =  new ContentValues();
        valueFalse.put("ativo",false);
        Integer count = db.update(table,valueFalse,whereClauseDifferent,whereArgs);
        Integer insertResult =db.update(table,value,whereClause,whereArgs);
        db.close();
        return insertResult > 0;
    }

    public boolean tapCartao(Boolean ativo){
        Log.d("SQLITE_CARTAO", "=== tapCartao() chamado com ativo=" + ativo + " ===");
        db = getWritableDatabase();

        Cursor cursor = db.query("tapCartao", new String[]{"ativo"},
                "", null,
                null, null, null);
        String ativoBanco = null;
        if (cursor != null && cursor.moveToFirst()) {
            ativoBanco = cursor.getString(cursor.getColumnIndexOrThrow("ativo"));
            Log.d("SQLITE_CARTAO", "Registro existente encontrado: ativo=" + ativoBanco);
            cursor.close();
        } else {
            Log.d("SQLITE_CARTAO", "Nenhum registro existente. Será feito INSERT.");
        }

        ContentValues value =  new ContentValues();
        Boolean result = false;
        value.put("ativo",ativo);
        
        if(ativoBanco == null){
            long insertResult = db.insert("tapCartao",null,value);
            result = insertResult > 0;
            Log.i("SQLITE_CARTAO", "INSERT executado. ID inserido: " + insertResult + ", result=" + result);
        }else{
            String whereClause = "id = ?";
            String[] whereArgs = {String.valueOf(1)};
            Integer count = db.update("tapCartao",value,whereClause,whereArgs);
            if(count > 0){
                result = true;
            }
            Log.i("SQLITE_CARTAO", "UPDATE executado. Linhas afetadas: " + count + ", result=" + result);
        }

        db.close();
        Log.d("SQLITE_CARTAO", "=== tapCartao() finalizado. Retornando: " + result + " ===");
        return result;
    }

    public boolean getCartaoEnabled(){
        db = getWritableDatabase();
        Cursor cursor = db.query("tapCartao", new String[]{"ativo"},
                "", null,
                null, null, null);
        String ativoBanco = null;
        if (cursor != null && cursor.moveToFirst()) {
            ativoBanco = cursor.getString(cursor.getColumnIndexOrThrow("ativo"));
            Log.d("SQLITE_CARTAO", "Valor lido do banco: " + ativoBanco);
            cursor.close();
            if(ativoBanco.equals("1")){
                Log.i("SQLITE_CARTAO", "Retornando true (cartão habilitado)");
                return true;
            }
        } else {
            Log.w("SQLITE_CARTAO", "Nenhum registro encontrado na tabela tapCartao");
        }
        Log.i("SQLITE_CARTAO", "Retornando false (cartão desabilitado)");
        return false;

    }

    public byte[] getImageData(String file_name) {
        SQLiteDatabase db = this.getReadableDatabase();
        byte[] imageData = null;

        try {
            Cursor cursor = db.query("tapImage", new String[]{"image_data","ativo"},
                    "file_name = ?", new String[]{String.valueOf(file_name)},
                    null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                try {
                    // Tenta ler a imagem
                    imageData = cursor.getBlob(cursor.getColumnIndexOrThrow("image_data"));
                    Log.d("Sqlite_Read", "Imagem lida com sucesso: " + imageData.length + " bytes");

                    if(cursor.getString(cursor.getColumnIndexOrThrow("ativo")).equals("false")){
                        update("tapImage",file_name,true);
                    }
                } catch (Exception e) {
                    // Se falhar ao ler (blob muito grande), deletar registro
                    Log.w("Sqlite_Read", "Blob muito grande, deletando registro: " + e.getMessage());
                    try {
                        db.delete("tapImage", "file_name = ?", new String[]{file_name});
                        Log.d("Sqlite_Read", "Registro deletado com sucesso");
                    } catch (Exception deleteError) {
                        Log.e("Sqlite_Read", "Erro ao deletar registro: " + deleteError.getMessage());
                    }
                    imageData = null;
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e("Sqlite_Read", "Erro ao ler imagem: " + e.getMessage(), e);
            imageData = null;
        } finally {
            db.close();
        }

        return imageData;
    }
    public byte[] getActiveImageData() {
        SQLiteDatabase db = this.getReadableDatabase();
        byte[] imageData = null;
        try {
            Cursor cursor = db.query("tapImage", new String[]{"image_data", "ativo"},
                    "ativo = ?", new String[]{String.valueOf("1")},
                    null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                try {
                    imageData = cursor.getBlob(cursor.getColumnIndexOrThrow("image_data"));
                    Log.d("Sqlite_ActiveRead", "Imagem ativa lida com sucesso: " + imageData.length + " bytes");
                } catch (Exception blobError) {
                    Log.w("Sqlite_ActiveRead", "Blob muito grande, deletando registro: " + blobError.getMessage());
                    try {
                        db.delete("tapImage", "ativo = ?", new String[]{"1"});
                        Log.d("Sqlite_ActiveRead", "Registro ativo deletado");
                    } catch (Exception deleteError) {
                        Log.e("Sqlite_ActiveRead", "Erro ao deletar: " + deleteError.getMessage());
                    }
                    imageData = null;
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e("Sqlite_ActiveRead", "Erro geral: " + e.getMessage(), e);
        }
        db.close();
        return imageData;
    }
}