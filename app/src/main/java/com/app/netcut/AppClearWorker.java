package com.app.netcut;

import static android.content.ContentValues.TAG;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import android.util.Log;


public class AppClearWorker extends Worker {
NetcutRunner netcutRunner;
    public AppClearWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        // Run your heavy task or function here
        try {
            netcutRunner.stop();
            Log.d(TAG, "Doing work");
        } catch (Exception e){
           
        }

        return Result.success();
    }
}
