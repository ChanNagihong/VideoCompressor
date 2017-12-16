package com.nagihong.videocompressor;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.start)
                .setOnClickListener(v -> {
                    if (checkPermission()) {
                        clickStart();
                    }
                });
    }

    void clickStart() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_PICK);
        intent.setType("video/*");
        startActivityForResult(intent, 123);
    }

    void showInput(String path) {
        ((TextView) findViewById(R.id.input)).setText(String.format(Locale.getDefault(), "input: %s", path));
    }

    void showOutput(String path) {
        ((TextView) findViewById(R.id.output)).setText(String.format(Locale.getDefault(), "output: %s", path));
    }

    void compressVideo(String path) {
        showInput(path);
        String output = Environment.getExternalStorageDirectory() + File.separator + System.currentTimeMillis() + ".mp4";
        new Thread() {
            @Override
            public void run() {
                super.run();
                new VideoCompressor().compressVideo(path, output);
                findViewById(R.id.output).post(() -> {
                    showOutput(output);
                    hideProgress();
                });
            }
        }.start();
    }

    void showProgress() {
        findViewById(R.id.progressbar).setVisibility(View.VISIBLE);
    }

    void hideProgress() {
        findViewById(R.id.progressbar).setVisibility(View.GONE);
    }

    //============================ callback ================================================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 123) {
            if (null == data) return;
            String path = getPathFromGalleryUri(this, data.getData());
            if (TextUtils.isEmpty(path)) return;
            showProgress();
            compressVideo(path);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 124) {
            boolean allPermissionsGranted = true;
            if (grantResults.length == 0) {
                allPermissionsGranted = false;
            } else {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        allPermissionsGranted = false;
                    }
                }
            }
            if (!allPermissionsGranted) {
                Toast.makeText(this, "please give me a permission to save the output file", Toast.LENGTH_LONG).show();
            } else {
                clickStart();
            }
        }
    }

    private boolean checkPermission() {
        List<String> permissions = new LinkedList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (permissions.size() > 0) {
            String[] arr = new String[permissions.size()];
            permissions.toArray(arr);
            ActivityCompat.requestPermissions(this, arr, 124);
            return false;
        }
        return true;
    }

    public static String getPathFromGalleryUri(Context context, Uri contentUri) {
        Cursor cursor;
        String[] proj = {MediaStore.Images.Media.DATA};
        cursor = context.getContentResolver().query(contentUri, proj, null, null, null);
        if (null == cursor) return null;

        int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
        cursor.moveToFirst();
        String result = cursor.getString(column_index);

        cursor.close();
        return result;
    }

}
