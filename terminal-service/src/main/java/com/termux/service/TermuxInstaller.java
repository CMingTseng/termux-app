package com.termux.service;

import android.content.Context;
import android.os.Environment;
import android.system.Os;
import android.util.Log;
import android.util.Pair;
import android.util.SparseBooleanArray;

import com.termux.terminal.EmulatorDebug;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import androidx.annotation.IntDef;
import androidx.lifecycle.MutableLiveData;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on
 * that we do not create a broken $PREFIX folder below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging folder, $STAGING_PREFIX, is {@link #deleteFolder(File)} if left over from broken
 * installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip
 * input stream continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to
 * setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if
 * necessary.
 */
public final class TermuxInstaller {
    private static final String TAG = "TermuxInstaller";
    private static TermuxInstaller sInstaller;

    @IntDef(value = {
        STEP_INIT, STEP_CHECK_DIRECTORY, STEP_CHECK_STAGING_DIRECTORY, STEP_GET_BOOTSTRAP_FILE, STEP_UNZIP_BOOTSTRAP_FILE, STEP_SYMLINK_BOOTSTRAP_FILE, STEP_RENAME_FOLDER, STEP_DONE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InstallStep {
    }

    public static final int STEP_INIT = 0;
    public static final int STEP_CHECK_DIRECTORY = 1;
    public static final int STEP_CHECK_STAGING_DIRECTORY = 2;
    public static final int STEP_GET_BOOTSTRAP_FILE = 3;
    public static final int STEP_UNZIP_BOOTSTRAP_FILE = 4;
    public static final int STEP_SYMLINK_BOOTSTRAP_FILE = 5;
    public static final int STEP_RENAME_FOLDER = 6;
    public static final int STEP_DONE = 7;
    public final MutableLiveData<SparseBooleanArray> mInstallerInfoObserver;

    private TermuxInstaller() {
        mInstallerInfoObserver = new MutableLiveData<>();
    }

    public final static TermuxInstaller getInstaller() {
        synchronized (TermuxInstaller.class) {
            if (sInstaller == null) {
                sInstaller = new TermuxInstaller();
            }
        }
        return sInstaller;
    }

    /**
     * Performs setup if necessary.
     */
    public void setupIfNeeded() {
        final SparseBooleanArray step = new SparseBooleanArray();
        step.put(STEP_INIT, true);
        final File PREFIX_FILE = new File(TermuxConfig.PREFIX_PATH);
        if (PREFIX_FILE.isDirectory()) {
            step.put(STEP_INIT, false);
            mInstallerInfoObserver.postValue(step);
            return;
        }
        mInstallerInfoObserver.postValue(step);

        new Thread() {
            @Override
            public void run() {
                try {
                    final String STAGING_PREFIX_PATH = TermuxConfig.FILES_PATH + "/usr-staging";
                    final File STAGING_PREFIX_FILE = new File(STAGING_PREFIX_PATH);

                    if (STAGING_PREFIX_FILE.exists()) {
                        deleteFolder(STAGING_PREFIX_FILE);
                    }
                    step.append(STEP_CHECK_STAGING_DIRECTORY, true);
                    mInstallerInfoObserver.postValue(step);
                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = STAGING_PREFIX_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    ensureDirectoryExists(new File(newPath).getParentFile());
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(STAGING_PREFIX_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                        step.append(STEP_UNZIP_BOOTSTRAP_FILE, true);
                        mInstallerInfoObserver.postValue(step);
                    }

                    if (symlinks.isEmpty()) {
                        step.append(STEP_SYMLINK_BOOTSTRAP_FILE, false);
                        mInstallerInfoObserver.postValue(step);
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    }

                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                        step.append(STEP_SYMLINK_BOOTSTRAP_FILE, true);
                        mInstallerInfoObserver.postValue(step);
                    }

                    if (!STAGING_PREFIX_FILE.renameTo(PREFIX_FILE)) {
                        step.append(STEP_RENAME_FOLDER, false);
                        mInstallerInfoObserver.postValue(step);
                        throw new RuntimeException("Unable to rename staging folder");
                    }
                    step.append(STEP_DONE, true);
                    mInstallerInfoObserver.postValue(step);
                } catch (final Exception e) {
                    Log.e(EmulatorDebug.LOG_TAG, "Bootstrap error", e);
                    step.put(STEP_DONE, false);
                    mInstallerInfoObserver.postValue(step);
                } finally {
                    step.append(STEP_DONE, true);
                    mInstallerInfoObserver.postValue(step);
                }
            }
        }.start();
    }

    private static void ensureDirectoryExists(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new RuntimeException("Unable to create directory: " + directory.getAbsolutePath());
        }
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

    /**
     * Delete a folder and all its content or throw. Don't follow symlinks.
     */
    public void deleteFolder(File fileOrDirectory) throws IOException {
        if (fileOrDirectory.getCanonicalPath().equals(fileOrDirectory.getAbsolutePath()) && fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();

            if (children != null) {
                for (File child : children) {
                    deleteFolder(child);
                }
            }
        }

        if (!fileOrDirectory.delete()) {
            throw new RuntimeException("Unable to delete " + (fileOrDirectory.isDirectory() ? "directory " : "file ") + fileOrDirectory.getAbsolutePath());
        }
    }

    public void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        new Thread() {
            public void run() {
                try {
                    File storageDir = new File(TermuxConfig.HOME_PATH, "storage");

                    if (storageDir.exists()) {
                        try {
                            deleteFolder(storageDir);
                        } catch (IOException e) {
                            Log.e(LOG_TAG, "Could not delete old $HOME/storage, " + e.getMessage());
                            return;
                        }
                    }

                    if (!storageDir.mkdirs()) {
                        Log.e(LOG_TAG, "Unable to mkdirs() for $HOME/storage");
                        return;
                    }

                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    final File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 1) {
                        for (int i = 1; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Error setting up link", e);
                }
            }
        }.start();
    }
}
