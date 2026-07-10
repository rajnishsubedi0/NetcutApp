package com.app.netcut;


import static android.content.ContentValues.TAG;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.tabs.TabLayout;


public class CleanupService extends Service {


        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            return START_STICKY;
        }

    @Override
    public void onTaskRemoved(Intent rootIntent) {

        Log.e(TAG, "App cleared!");


        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AppClearWorker.class)
                .build();

        WorkManager.getInstance(getApplicationContext()).enqueue(request);
        stopSelf();
    }
        @Override
        public IBinder onBind(Intent intent) { return null; }

}