package com.gamerturbo.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/** Shizuku UserService bridge. Uses the documented Shizuku UserService AIDL bridge. */
public final class ShizukuShell {
    private static final Object LOCK = new Object();
    private static volatile IGamerTurboUserService remote;
    private static volatile boolean binding;
    private static volatile CountDownLatch connected = new CountDownLatch(0);
    private static Shizuku.UserServiceArgs serviceArgs;
    private static Context appContext;

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = IGamerTurboUserService.Stub.asInterface(binder);
            binding = false;
            connected.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            binding = false;
        }
    };

    private ShizukuShell() { }

    public static boolean isRunning() {
        try { return Shizuku.pingBinder(); }
        catch (Throwable ignored) { return false; }
    }

    public static boolean isAuthorized() {
        try { return isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable ignored) { return false; }
    }

    public static String state() {
        if (!isRunning()) return "OFF";
        return isAuthorized() ? "READY" : "NEED_PERMISSION";
    }

    private static boolean connect(Context context) {
        if (!isAuthorized()) return false;
        CountDownLatch waitForConnection;
        synchronized (LOCK) {
            if (remote != null) return true;
            if (!binding) {
                appContext = context.getApplicationContext();
                serviceArgs = new Shizuku.UserServiceArgs(
                        new ComponentName(appContext, GamerTurboUserService.class))
                        .daemon(false)
                        .debuggable(false)
                        .processNameSuffix("gamer_turbo_shell")
                        .version(2);
                connected = new CountDownLatch(1);
                binding = true;
                try {
                    Shizuku.bindUserService(serviceArgs, CONNECTION);
                } catch (Throwable error) {
                    binding = false;
                    return false;
                }
            }
            waitForConnection = connected;
        }
        try {
            boolean ready = waitForConnection.await(5, TimeUnit.SECONDS) && remote != null;
            if (!ready) {
                synchronized (LOCK) {
                    if (connected == waitForConnection && remote == null) binding = false;
                }
            }
            return ready;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (LOCK) { if (connected == waitForConnection && remote == null) binding = false; }
            return false;
        }
    }

    public static Result execute(Context context, String command) {
        if (command == null || command.length() > 6000) return new Result(false, "Comando inválido.", 2);
        if (!connect(context)) return new Result(false, "Shizuku não conectado, sem autorização ou serviço indisponível.", -1);
        try {
            String raw = remote.execLine(command);
            if (raw == null) return new Result(false, "O serviço não retornou dados.", -1);
            int marker = raw.indexOf('\n');
            String header = marker >= 0 ? raw.substring(0, marker) : raw;
            String output = marker >= 0 ? raw.substring(marker + 1).trim() : "";
            int code = -1;
            if (header.startsWith("__GT_EXIT__:")) {
                try { code = Integer.parseInt(header.substring("__GT_EXIT__:".length())); }
                catch (NumberFormatException ignored) { code = -1; }
            }
            return new Result(code == 0, output, code);
        } catch (RemoteException | RuntimeException error) {
            remote = null;
            String message = error.getMessage();
            return new Result(false, message == null ? error.getClass().getSimpleName() : message, -1);
        }
    }

    public static final class Result {
        public final boolean success;
        public final String output;
        public final int exitCode;
        Result(boolean success, String output, int exitCode) {
            this.success = success;
            this.output = output;
            this.exitCode = exitCode;
        }
    }
}
