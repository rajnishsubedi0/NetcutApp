package com.app.netcut;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ExtractFile {
    public void extractTheFile(Context context, int rawResId, String fileName) throws IOException {
        File targetFile = new File(context.getFilesDir(), fileName);
        String path = context.getFilesDir().getAbsolutePath();
        Log.e(TAG, path);

        // Check if file already exists - if so, skip extraction
        if (targetFile.exists()) {
            Log.e(TAG, "File already exists: " + fileName + " - Skipping extraction");
            return;
        }

        try (InputStream input = context.getResources().openRawResource(rawResId);
             FileOutputStream output = new FileOutputStream(targetFile)) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }

            // Log success message only ONCE after extraction is complete
            Log.e(TAG, "Netcut binary Extracted successfully");

        } catch (IOException e) {
            Log.e(TAG, "Unable to extract: " + e.getMessage());
            throw e; // Re-throw to let caller know extraction failed
        }
    }
}