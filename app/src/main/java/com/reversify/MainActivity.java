package com.reversify;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import com.github.hiteshsondhi88.libffmpeg.ExecuteBinaryResponseHandler;
import com.github.hiteshsondhi88.libffmpeg.FFmpeg;
import com.github.hiteshsondhi88.libffmpeg.LoadBinaryResponseHandler;
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegCommandAlreadyRunningException;
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegNotSupportedException;

import java.io.File;

import butterknife.BindView;
import butterknife.ButterKnife;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SELECT_VIDEO = 1001;
    private static final int REQUEST_CODE_OPEN_VIDEO = 1002;
    private static final int REQUEST_PERMISSIONS = 2001;

    private static final String TAG = "MainActivity";

    NotificationManager notificationManager;
    NotificationCompat.Builder notificationBuilder;

    @BindView(R.id.btn_select_video)
    Button btn_selectVideo;

    @BindView(R.id.tv_videoURI)
    TextView tvVideoURI;

    @BindView(R.id.cb_dx)
    CheckBox cbDx;
    @BindView(R.id.cb_speed)
    CheckBox cbSpeed;

    private String videoFileName;
    private String[] command;

    private Dialog loaderOverlay;
    private FFmpeg ffmpeg;

    private static final float SPEED = 0.8f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ButterKnife.bind(this);

        checkPermissions();
        createReversifyDirectory();
        loadFFmpegBinary();

        btn_selectVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent mediaIntent = new Intent(Intent.ACTION_GET_CONTENT);
                mediaIntent.setType("video/*");
                startActivityForResult(mediaIntent, REQUEST_CODE_SELECT_VIDEO);
            }
        });

        cbDx.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                if (cbDx.isChecked()) cbSpeed.setChecked(false);
            }
        });

        cbSpeed.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                if (cbSpeed.isChecked()) cbDx.setChecked(false);
            }
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(MainActivity.this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {

            if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                Toast.makeText(MainActivity.this, "Storage Permissions not allowed, app might not work!", Toast.LENGTH_SHORT).show();
            } else {
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSIONS);
            }
        }
    }

    private void createReversifyDirectory() {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Log.d(TAG, "There is no External Storage Directory!");
        } else {
            Runnable directoryCreateRunnable = new Runnable() {
                @Override
                public void run() {
                    boolean isDirectoryCreated = false;
                    while (!isDirectoryCreated) {
                        isDirectoryCreated = new File(Environment.getExternalStorageDirectory() + File.separator + "Reversify").mkdirs();
                    }
                    Log.d(TAG, "createReversifyDirectory: 'Reversify' Directory Created Successfully!");
                }
            };
            Thread directoryCreateThread = new Thread(directoryCreateRunnable);
            directoryCreateThread.start();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SELECT_VIDEO &&
                resultCode == Activity.RESULT_OK) {
            Uri videoURI = data.getData();

            if (videoURI != null) {
                String videoPath = FileSizeUtil.getFileAbsolutePath(MainActivity.this, videoURI);
                Log.d(TAG, videoPath);
                String fileName[] = videoPath.split(File.separator);
                videoFileName = fileName[fileName.length - 1];
                String executableCmd = "";
                if (cbDx.isChecked()) {
                    executableCmd = "-i " + videoPath + " -vf reverse -af areverse -preset ultrafast " + Environment.getExternalStorageDirectory().getPath() + File.separator + "Reversify" + File.separator + videoFileName + " -y -threads 2";
                } else if (cbSpeed.isChecked()) {
                    executableCmd = "-i " + videoPath + " -filter_complex " + "[0:v]setpts=" + SPEED + "*PTS[v];[0:a]atempo=" + SPEED + "[a] " + "-map [v] -map [a] -preset ultrafast " + Environment.getExternalStorageDirectory().getPath() + File.separator + "Reversify" + File.separator + videoFileName + " -y -threads 2";
                }

                command = executableCmd.split(" ");
                notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                notificationBuilder = new NotificationCompat.Builder(this);
                notificationBuilder.setContentTitle("Reversify")
                        .setContentText("Reversifying your video, please wait...")
                        .setSmallIcon(R.drawable.ic_gesture_black_24dp);
                notificationBuilder.setProgress(0, 0, true);
                notificationManager.notify(1, notificationBuilder.build());

                loadSpinner();

                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                executeFFmpegCommands();
                            }
                        }
                ).start();

            } else {
                Toast.makeText(MainActivity.this, "The selected video is not accessible!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String getRealPathFromURI(Uri contentURI) {
        String result;
        Cursor cursor = getContentResolver().query(contentURI, null, null, null, null);
        if (cursor == null) {
            result = contentURI.getPath();
        } else {
            cursor.moveToFirst();
            int idx = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
            result = cursor.getString(idx);
            cursor.close();
        }
        return result;
    }


    private void loadFFmpegBinary() {
        ffmpeg = FFmpeg.getInstance(getApplicationContext());
        try {
            ffmpeg.loadBinary(new LoadBinaryResponseHandler() {
                @Override
                public void onFailure() {
                }

                @Override
                public void onSuccess() {
                }

                @Override
                public void onStart() {
                }

                @Override
                public void onFinish() {
                }
            });
        } catch (FFmpegNotSupportedException e) {
            Toast.makeText(MainActivity.this, "FFmpeg is not supported by this device!", Toast.LENGTH_SHORT).show();
        }
    }

    private void executeFFmpegCommands() {
        try {
            final long startTime = System.currentTimeMillis();
            ffmpeg.execute(command, new ExecuteBinaryResponseHandler() {
                @Override
                public void onSuccess(String message) {
                    Toast.makeText(MainActivity.this, "Video Processing Successful!", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "onSuccess: " + message);
                    Log.e("time", (System.currentTimeMillis() - startTime) + "");
                    tvVideoURI.setText(videoFileName + "耗时：" + (System.currentTimeMillis() - startTime) / 1000 + "s");
                    loadVideo();
                }

                @Override
                public void onProgress(String message) {
                    Log.d(TAG, "onProgress: " + message);
                }

                @Override
                public void onFailure(String message) {
                    Toast.makeText(MainActivity.this, "Video Processing Failed!", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "onFailure: " + message);
                }

                @Override
                public void onStart() {
                }

                @Override
                public void onFinish() {
                    notificationManager.cancel(1);
                    loaderOverlay.dismiss();
                }
            });
        } catch (FFmpegCommandAlreadyRunningException e) {
            Toast.makeText(MainActivity.this, "FFmpeg is already running, please wait!", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadSpinner() {
        loaderOverlay = new Dialog(MainActivity.this);

        loaderOverlay.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        loaderOverlay.getWindow().setFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND, WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        loaderOverlay.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        loaderOverlay.setContentView(R.layout.loader_spinner);

        loaderOverlay.show();
    }

    private void loadVideo() {
        File directory = Environment.getExternalStorageDirectory();
        File videoFile = new File(directory, "/Reversify/" + videoFileName);

        MediaScannerConnection.scanFile(this, new String[]{videoFile.getPath()}, new String[]{"video/*"}, null);


        Intent intent = new Intent(Intent.ACTION_VIEW);
        //判断是否是AndroidN以及更高的版本
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Uri contentUri = FileProvider.getUriForFile(MainActivity.this, BuildConfig.APPLICATION_ID + ".fileProvider", videoFile);
            intent.setDataAndType(contentUri, "video/*");
        } else {
            intent.setDataAndType(Uri.fromFile(videoFile), "video/*");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        startActivity(intent);
    }
}
