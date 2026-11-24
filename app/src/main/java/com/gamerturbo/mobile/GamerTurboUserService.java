package com.gamerturbo.mobile;

import android.content.Context;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Shizuku UserService. Runs as shell (UID 2000) when Shizuku is started via ADB,
 * or with the identity supplied by the selected Shizuku backend.
 */
public final class GamerTurboUserService extends IGamerTurboUserService.Stub {
    public GamerTurboUserService() { super(); }
    public GamerTurboUserService(Context ignored) { super(); }

    @Override
    public String execLine(String command) throws RemoteException {
        if (command == null || command.length() > 6000 || command.indexOf('\0') >= 0) {
            return "__GT_EXIT__:2\nComando inválido.";
        }
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", "exec 2>&1; " + command)
                    .redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < 7000) output.append(line).append('\n');
                }
            }
            int code = process.waitFor();
            return "__GT_EXIT__:" + code + "\n" + output.toString().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "__GT_EXIT__:130\nExecução interrompida.";
        } catch (Throwable error) {
            String message = error.getMessage();
            return "__GT_EXIT__:1\n" + (message == null ? error.getClass().getSimpleName() : message);
        } finally {
            if (process != null) try { process.destroy(); } catch (Throwable ignored) { }
        }
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
