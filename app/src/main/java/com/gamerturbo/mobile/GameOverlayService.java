package com.gamerturbo.mobile;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class GameOverlayService extends Service {
    private static final String PREFS = "gamer_turbo_native";
    private static final String CHANNEL_ID = "gamer_turbo_overlay";
    private static final int NOTIFICATION_ID = 8101;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View bubbleView;
    private View panelView;
    private View brightnessPanelView;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams panelParams;
    private boolean panelOpen = false;
    private volatile boolean recording = false;
    private TextView batteryValue, ramValue, tempValue, thermalValue, sessionValue, memoryHint, shizukuState;
    private TextView performanceClean;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            if (panelOpen) { updateMetrics(); handler.postDelayed(this, 2000); }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForegroundCompat();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) { stopSelf(); return; }
        showBubble();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("overlay_enabled", true).apply();
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getLong("game_session_started", 0L) == 0L) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("game_session_started", System.currentTimeMillis()).apply();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_OVERLAY".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (bubbleView == null && !panelOpen) showBubble();
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Painel flutuante Gamer Turbo", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Atalho lateral para ferramentas e informações durante jogos.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void startForegroundCompat() {
        Intent openIntent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 8102, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, GameOverlayService.class).setAction("STOP_OVERLAY");
        PendingIntent stopPending = PendingIntent.getService(this, 8103, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Gamer Turbo ativo").setContentText("Toque na alça lateral durante a partida")
                .setContentIntent(openPending).setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Desativar", stopPending).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int strokeColor, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); drawable.setStroke(dp(1), strokeColor); return drawable;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextColor(color); view.setTextSize(size);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD); return view;
    }
    private TextView action(String label, Runnable callback) {
        TextView view = text(label, 10, Color.WHITE, true); view.setGravity(Gravity.CENTER); view.setPadding(dp(5), dp(9), dp(5), dp(9));
        view.setBackground(background(0xFF20242D, 0xFF3B424E, 9)); view.setOnClickListener(v -> callback.run()); return view;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private LinearLayout column() { LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); return col; }
    private void gap(LinearLayout parent, int h) { View v = new View(this); parent.addView(v, new LinearLayout.LayoutParams(1, dp(h))); }
    private void addWeighted(LinearLayout parent, View view, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(height), 1f); lp.setMargins(dp(3), 0, dp(3), 0); parent.addView(view, lp);
    }

    private WindowManager.LayoutParams newParams(int width, int height) {
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        return new WindowManager.LayoutParams(width, height, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
    }

    private void showBubble() {
        if (windowManager == null || bubbleView != null || panelView != null) return;
        TextView bubble = text("GT", 13, Color.WHITE, true); bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(background(0xFFE51E32, 0xFFFF828A, 13)); bubble.setElevation(dp(8));
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY; private float downX, downY; private long downAt;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN: startX=bubbleParams.x; startY=bubbleParams.y; downX=event.getRawX(); downY=event.getRawY(); downAt=System.currentTimeMillis(); return true;
                    case MotionEvent.ACTION_MOVE: bubbleParams.x=startX-(int)(event.getRawX()-downX); bubbleParams.y=startY+(int)(event.getRawY()-downY); try { windowManager.updateViewLayout(bubbleView,bubbleParams); } catch (Exception ignored) { } return true;
                    case MotionEvent.ACTION_UP: float d=Math.abs(event.getRawX()-downX)+Math.abs(event.getRawY()-downY); if(d<dp(12)&&System.currentTimeMillis()-downAt<600) showPanel(); return true;
                } return false;
            }
        });
        bubbleView=bubble; bubbleParams=newParams(dp(46),dp(54)); bubbleParams.gravity=Gravity.RIGHT|Gravity.CENTER_VERTICAL; bubbleParams.x=dp(2); bubbleParams.y=0;
        try { windowManager.addView(bubbleView,bubbleParams); } catch(Exception e) { bubbleView=null; stopSelf(); }
    }

    private LinearLayout metricCard(String label, TextView[] output) {
        LinearLayout card=column(); card.setPadding(dp(9),dp(8),dp(7),dp(7)); card.setBackground(background(0xFF191D25,0xFF2D333E,9));
        TextView l=text(label,7,0xFF9BA4B2,true); TextView value=text("--",14,Color.WHITE,true);
        card.addView(l); LinearLayout.LayoutParams v=new LinearLayout.LayoutParams(-1,-2); v.topMargin=dp(5); card.addView(value,v);
        output[0]=value; return card;
    }

    private void showPanel() {
        if (windowManager == null || panelView != null) return;
        removeBubble();
        LinearLayout root=column(); root.setPadding(dp(12),dp(11),dp(12),dp(12)); root.setBackground(background(0xF20D1016,0xFF454B57,16)); root.setElevation(dp(14));
        LinearLayout head=row(); TextView title=text("GAMER TURBO",13,Color.WHITE,true); head.addView(title,new LinearLayout.LayoutParams(0,-2,1)); head.addView(action("×",this::collapsePanel),new LinearLayout.LayoutParams(dp(32),dp(30))); root.addView(head);
        TextView sub=text("PAINEL DE PARTIDA  •  ANDROID",8,0xFF929CAB,true); sub.setPadding(0,dp(3),0,dp(9)); root.addView(sub);

        LinearLayout metrics=row(); TextView[] a=new TextView[1],b=new TextView[1],c=new TextView[1],d=new TextView[1];
        addWeighted(metrics,metricCard("BATERIA",a),62); addWeighted(metrics,metricCard("RAM LIVRE",b),62); addWeighted(metrics,metricCard("TEMP. BATERIA",c),62); addWeighted(metrics,metricCard("TÉRMICO",d),62); root.addView(metrics);
        batteryValue=a[0]; ramValue=b[0]; tempValue=c[0]; thermalValue=d[0];
        sessionValue=text("PARTIDA 00:00",8,0xFF9DA6B3,true); sessionValue.setPadding(dp(3),dp(8),0,dp(2)); root.addView(sessionValue);
        gap(root,8);

        TextView modeLabel=text("PERFIL DE JOGO",8,0xFF8E98A7,true); modeLabel.setPadding(0,0,0,dp(6)); root.addView(modeLabel);
        LinearLayout modes=row(); String selected=getSharedPreferences(PREFS,MODE_PRIVATE).getString("game_mode","normal");
        addWeighted(modes,modeButton("BÁSICO","normal",selected),40); addWeighted(modes,modeButton("GAMER","balanced",selected),40); addWeighted(modes,modeButton("EXTREMO","performance",selected),40); root.addView(modes);
        gap(root,8);

        TextView toolLabel=text("FERRAMENTAS",8,0xFF8E98A7,true); toolLabel.setPadding(0,0,0,dp(6)); root.addView(toolLabel);
        LinearLayout tools1=row(); addWeighted(tools1,action("FOCO",this::toggleDnd),40); addWeighted(tools1,action("CAPTURA",this::takeScreenshot),40); addWeighted(tools1,action(recording?"PARAR REC":"GRAVAR",this::toggleRecording),40); root.addView(tools1);
        gap(root,6);
        LinearLayout tools2=row(); addWeighted(tools2,action("CRONÔMETRO",this::showSessionTime),40); addWeighted(tools2,action("BRILHO",this::showBrightnessPanel),40); addWeighted(tools2,action("WI-FI",this::openWifiPanel),40); root.addView(tools2);
        gap(root,7);
        LinearLayout tools3=row(); addWeighted(tools3,action("CENTRAL",this::openMain),40); addWeighted(tools3,action("FECHAR",this::collapsePanel),40); addWeighted(tools3,action("OCULTAR",this::collapsePanel),40); root.addView(tools3);
        gap(root,7);
        if ("performance".equals(selected)) {
            performanceClean=action("ANALISAR RAM",this::cleanCachedProcesses);
            performanceClean.setBackground(background(0xFF8F1421,0xFFEA3949,9)); root.addView(performanceClean,new LinearLayout.LayoutParams(-1,dp(39)));
            memoryHint=text("Leitura de memória sem encerrar processos do sistema.",8,0xFFAFBAC8,false);
            memoryHint.setPadding(dp(2),dp(6),dp(2),0); root.addView(memoryHint);
        }
        shizukuState=null;
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setBackground(background(0xF20D1016,0xFF454B57,16));
        scroller.addView(root, new ScrollView.LayoutParams(-1, -2));
        panelView=scroller;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        panelParams=newParams(dp(328), Math.max(dp(220), Math.min(dp(560), screenHeight-dp(24))));
        panelParams.gravity=Gravity.RIGHT|Gravity.CENTER_VERTICAL; panelParams.x=dp(9); panelParams.y=0;
        try { windowManager.addView(panelView,panelParams); panelOpen=true; updateMetrics(); handler.removeCallbacks(refreshTask); handler.postDelayed(refreshTask,2000); }
        catch(Exception e) { panelView=null; panelOpen=false; showBubble(); }
    }

    private View modeButton(String label,String mode,String selected) {
        int accent="normal".equals(mode)?0xFF55A9FF:"balanced".equals(mode)?0xFFF5C84B:0xFFEF3445;
        int activeBg="normal".equals(mode)?0xFF162C42:"balanced".equals(mode)?0xFF3A321B:0xFF3A1D27;
        TextView v=text(label,8,Color.WHITE,true); v.setGravity(Gravity.CENTER); v.setBackground(background(mode.equals(selected)?activeBg:0xFF20242C,mode.equals(selected)?accent:0xFF3B424D,8));
        v.setOnClickListener(view->{
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("game_mode",mode).apply();
            applySelectedOverlayProfile(mode);
            Toast.makeText(this,"Perfil "+label+" selecionado",Toast.LENGTH_SHORT).show();
            collapsePanel(); showPanel();
        }); return v;
    }

    private void applySelectedOverlayProfile(String mode) {
        SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        if (!prefs.getBoolean("gt_profile_active",false)) return;
        String packageName=prefs.getString("gt_profile_package","");
        if (!packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) return;
        float maxHz=60f;
        try {
            android.view.Display display=windowManager.getDefaultDisplay();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (android.view.Display.Mode supported:display.getSupportedModes()) maxHz=Math.max(maxHz,supported.getRefreshRate());
            } else maxHz=display.getRefreshRate();
        } catch (Throwable ignored) { }
        final float refresh=maxHz;
        boolean cool=true;
        try {
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power!=null) cool=power.getCurrentThermalStatus()<=PowerManager.THERMAL_STATUS_LIGHT;
        } catch (Throwable ignored) { cool=false; }
        final boolean thermalSafe=cool;
        new Thread(()->{
            String result="normal".equals(mode)?GameOptimizer.restore(GameOverlayService.this)
                    :GameOptimizer.apply(GameOverlayService.this,packageName,mode,refresh,thermalSafe);
            prefs.edit().putString("gt_last_profile_report",result).apply();
            handler.post(()->Toast.makeText(GameOverlayService.this,"Perfil de jogo atualizado.",Toast.LENGTH_SHORT).show());
        },"GamerTurbo-OverlayProfile").start();
    }

    private void updateMetrics() {
        try {
            Intent battery=getRegisterBattery();
            if (battery != null && batteryValue != null) {
                int level=battery.getIntExtra("level",-1),scale=battery.getIntExtra("scale",100),temp=battery.getIntExtra("temperature",-1);
                int pct=level>=0&&scale>0?Math.round(level*100f/scale):-1;
                batteryValue.setText(pct>=0?pct+"%":"--");
                tempValue.setText(temp>=0?String.format(java.util.Locale.US,"%.1f°",temp/10f):"--");
            }
            ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE); if(am!=null&&ramValue!=null){ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo();am.getMemoryInfo(mi);float freeMb=mi.availMem/1048576f;ramValue.setText(freeMb>=1024?String.format(java.util.Locale.US,"%.1f GB",freeMb/1024f):Math.round(freeMb)+" MB");}
            if (thermalValue != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
                if (power != null) thermalValue.setText(thermalLabel(power.getCurrentThermalStatus()));
            }
            long started=getSharedPreferences(PREFS,MODE_PRIVATE).getLong("game_session_started",System.currentTimeMillis());
            if(sessionValue!=null)sessionValue.setText("PARTIDA  "+formatDuration(Math.max(0,(System.currentTimeMillis()-started)/1000)));
        } catch(Exception ignored) { }
    }
    private Intent getRegisterBattery() { return registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); }
    private String thermalLabel(int status) {
        switch(status){case PowerManager.THERMAL_STATUS_LIGHT:return "LEVE";case PowerManager.THERMAL_STATUS_MODERATE:return "MÉDIO";case PowerManager.THERMAL_STATUS_SEVERE:return "ALTO";case PowerManager.THERMAL_STATUS_CRITICAL:case PowerManager.THERMAL_STATUS_EMERGENCY:case PowerManager.THERMAL_STATUS_SHUTDOWN:return "CRÍTICO";default:return "NORMAL";}
    }
    private String formatDuration(long seconds) { long h=seconds/3600,m=(seconds%3600)/60,s=seconds%60; return String.format(java.util.Locale.US,"%02d:%02d:%02d",h,m,s); }
    private void showSessionTime() { updateMetrics(); Toast.makeText(this,sessionValue==null?"Cronômetro de partida ativo":sessionValue.getText(),Toast.LENGTH_SHORT).show(); }

    private void showBrightnessPanel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !Settings.System.canWrite(this)) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(this, "Permita modificar as configurações do sistema e toque em BRILHO novamente.", Toast.LENGTH_LONG).show();
            } catch (Exception e) { Toast.makeText(this, "Não foi possível abrir a permissão de brilho.", Toast.LENGTH_SHORT).show(); }
            return;
        }
        removeBrightnessPanel();
        LinearLayout box = column(); box.setPadding(dp(14),dp(12),dp(14),dp(12));
        box.setBackground(background(0xF20D1016,0xFFEA3445,13));
        TextView label = text("BRILHO DA TELA",10,Color.WHITE,true); box.addView(label);
        int current = 128; try { current = Settings.System.getInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,128); } catch(Exception ignored) { }
        TextView value = text(Math.round(current/255f*100)+"%",11,0xFFFF7A83,true); value.setPadding(0,dp(8),0,0); box.addView(value);
        SeekBar seek = new SeekBar(this); seek.setMax(255); seek.setProgress(current); seek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFEF3446));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){value.setText(Math.round(progress/255f*100)+"%");}
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,dp(38)); slp.topMargin=dp(5); box.addView(seek,slp);
        TextView apply=action("APLICAR",()->{try{Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,seek.getProgress());Toast.makeText(this,"Brilho atualizado",Toast.LENGTH_SHORT).show();removeBrightnessPanel();}catch(Exception e){Toast.makeText(this,"O Android não permitiu alterar o brilho.",Toast.LENGTH_SHORT).show();}});
        LinearLayout.LayoutParams alp=new LinearLayout.LayoutParams(-1,dp(36)); alp.topMargin=dp(8); box.addView(apply,alp);
        brightnessPanelView=box; WindowManager.LayoutParams lp=newParams(dp(260),WindowManager.LayoutParams.WRAP_CONTENT);lp.gravity=Gravity.RIGHT|Gravity.CENTER_VERTICAL;lp.x=dp(18);lp.y=dp(205);
        try{windowManager.addView(brightnessPanelView,lp);}catch(Exception e){brightnessPanelView=null;Toast.makeText(this,"Não foi possível abrir o controle de brilho.",Toast.LENGTH_SHORT).show();}
    }

    private void removeBrightnessPanel(){if(brightnessPanelView!=null&&windowManager!=null){try{windowManager.removeView(brightnessPanelView);}catch(Exception ignored){}brightnessPanelView=null;}}

    private void openWifiPanel(){
        try{
            Intent intent;
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) intent=new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
            else intent=new Intent(Settings.ACTION_WIFI_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(intent);
        }catch(Exception e){Toast.makeText(this,"Não foi possível abrir os controles de Wi-Fi.",Toast.LENGTH_SHORT).show();}
    }

    private void cleanCachedProcesses() {
        if (!"performance".equals(getSharedPreferences(PREFS, MODE_PRIVATE).getString("game_mode", "normal"))) {
            Toast.makeText(this, "A análise detalhada de RAM fica disponível no modo Extremo.", Toast.LENGTH_SHORT).show();
            return;
        }
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        if (manager == null) {
            Toast.makeText(this, "Não foi possível consultar a memória.", Toast.LENGTH_SHORT).show();
            return;
        }
        manager.getMemoryInfo(info);
        long used = Math.max(0L, info.totalMem - info.availMem);
        String message = "RAM em uso: " + Math.round(used / 1048576f) + " MB de "
                + Math.round(info.totalMem / 1048576f) + " MB. Nenhum processo foi encerrado à força.";
        if (memoryHint != null) memoryHint.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        updateMetrics();
    }

    private void takeScreenshot() {
        if(!ShizukuShell.isAuthorized()){openMainForShizuku();return;}
        new Thread(()->{
            String path="/sdcard/Pictures/GamerTurbo/GT_"+System.currentTimeMillis()+".png";
            ShizukuShell.Result result=ShizukuShell.execute(GameOverlayService.this, "mkdir -p /sdcard/Pictures/GamerTurbo && screencap -p '"+path+"'");
            handler.post(()->Toast.makeText(this,result.success?"Captura salva em Pictures/GamerTurbo":("Falha na captura: "+result.output),Toast.LENGTH_LONG).show());
        },"GamerTurbo-Screenshot").start();
    }

    private void toggleRecording() {
        if(!ShizukuShell.isAuthorized()){openMainForShizuku();return;}
        if(recording){new Thread(()->ShizukuShell.execute(GameOverlayService.this, "pkill -2 screenrecord"),"GamerTurbo-StopRecord").start();recording=false;Toast.makeText(this,"Parando gravação...",Toast.LENGTH_SHORT).show();collapsePanel();return;}
        recording=true; collapsePanel(); Toast.makeText(this,"Gravação iniciada • até 3 minutos • sem áudio",Toast.LENGTH_LONG).show();
        new Thread(()->{
            String path="/sdcard/Movies/GamerTurbo/GT_"+System.currentTimeMillis()+".mp4";
            ShizukuShell.Result result=ShizukuShell.execute(GameOverlayService.this, "mkdir -p /sdcard/Movies/GamerTurbo && screenrecord --time-limit 180 '"+path+"'");
            recording=false;
            handler.post(()->Toast.makeText(this,result.success?"Vídeo salvo em Movies/GamerTurbo":("Gravação encerrada: "+result.output),Toast.LENGTH_LONG).show());
        },"GamerTurbo-ScreenRecord").start();
    }

    private void openMainForShizuku() {
        Intent intent=new Intent(this,MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);intent.putExtra("request_shizuku",true);startActivity(intent);
        Toast.makeText(this,"Inicie o Shizuku por depuração sem fio e autorize o Gamer Turbo.",Toast.LENGTH_LONG).show();
    }

    private void toggleDnd() {
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(manager==null)return;
        if(!manager.isNotificationPolicyAccessGranted()){Intent intent=new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(intent);Toast.makeText(this,"Autorize Não Perturbe e toque novamente.",Toast.LENGTH_LONG).show();return;}
        SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);boolean active=prefs.getBoolean("game_dnd_active",false);
        try{if(!active){prefs.edit().putInt("previous_dnd_filter",manager.getCurrentInterruptionFilter()).apply();manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);prefs.edit().putBoolean("game_dnd_active",true).apply();}
        else{manager.setInterruptionFilter(prefs.getInt("previous_dnd_filter",NotificationManager.INTERRUPTION_FILTER_ALL));prefs.edit().putBoolean("game_dnd_active",false).remove("previous_dnd_filter").apply();}
        Toast.makeText(this,active?"Não Perturbe desativado":"Não Perturbe ativado",Toast.LENGTH_SHORT).show();}catch(SecurityException e){Toast.makeText(this,"O Android bloqueou Não Perturbe.",Toast.LENGTH_SHORT).show();}
    }

    private void openMain(){Intent i=new Intent(this,MainActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(i);}
    private void collapsePanel(){handler.removeCallbacks(refreshTask);removeBrightnessPanel();removePanel();showBubble();}
    private void removeBubble(){if(bubbleView!=null&&windowManager!=null){try{windowManager.removeView(bubbleView);}catch(Exception ignored){}bubbleView=null;}}
    private void removePanel(){if(panelView!=null&&windowManager!=null){try{windowManager.removeView(panelView);}catch(Exception ignored){}panelView=null;panelOpen=false;}}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);removeBrightnessPanel();removeBubble();removePanel();getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("overlay_enabled",false).apply();if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
