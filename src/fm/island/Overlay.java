package fm.island;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

public final class Overlay extends Service {
    private static final int INK=0xff08080b, SURFACE=0xff22222a, ACCENT=0xffe4d9ff, TEXT=0xfff6f7fc;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private LinearLayout sheet, controls, panel;
    private TextView title, subtitle, clock;
    private ImageButton toggle,detail,prev,next;
    private float horizontal=.5f;
    private ImageView cover;
    private SeekBar progress;
    private boolean expanded, dragging;
    private Bitmap art;
    private int tint=IslandVisuals.FALLBACK;
    private LyricStrip lyrics, compactLyrics;
    private IslandVisuals.Pulse pulse;
    private Lyrics currentLyrics;
    private Store store;
    private String lyricKey="";
    private final java.util.Map<String,Lyrics> lyricCache=new java.util.HashMap<>();
    private final java.util.concurrent.ExecutorService lyricWorker=java.util.concurrent.Executors.newFixedThreadPool(2);
    private android.animation.ValueAnimator resize;
    private MediaController remote;
    private Player local;
    private long duration;
    private final Runnable refresh=new Runnable() {
        @Override public void run() {
            if (!Settings.canDrawOverlays(Overlay.this)) { stopSelf(); return; }
            if(sheet!=null){
                sheet.setVisibility(Main.fullPlayerVisible?View.GONE:View.VISIBLE);
                if(!Main.fullPlayerVisible)update();
            }
            ui.postDelayed(this,expanded?85:145);
        }
    };

    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent!=null && "close".equals(intent.getAction())) {
            new Store(this).prefs.edit().putBoolean("overlay",false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if(!Settings.canDrawOverlays(this)) {stopSelf();return START_NOT_STICKY;}
        NotificationManager notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("overlay","Плавающий плеер",NotificationManager.IMPORTANCE_LOW));
        int immutable=PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open=PendingIntent.getActivity(this,61,new Intent(this,Main.class),immutable);
        PendingIntent close=PendingIntent.getService(this,61,new Intent(this,Overlay.class).setAction("close"),immutable);
        startForeground(61,new Notification.Builder(this,"overlay").setSmallIcon(R.drawable.island)
            .setContentTitle("Плавающий плеер").setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Скрыть",close).build()).build());
        if(sheet==null) {
            try { show(); }
            catch(WindowManager.BadTokenException | SecurityException e) { stopSelf(); return START_NOT_STICKY; }
        }
        ui.removeCallbacks(refresh);
        ui.post(refresh);
        return START_STICKY;
    }

    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable rounded(int color,int radius){
        GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;
    }
    private TextView text(String value,int sp,int color){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);
        t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);return t;
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(1);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private ImageButton button(int icon,String description,boolean primary,Runnable action){
        ImageButton b=new ImageButton(this);b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(primary?INK:TEXT));
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x39ffffff),
            rounded(primary?ACCENT:SURFACE,22),null));
        b.setContentDescription(description);b.setOnClickListener(v->action.run());return b;
    }
    private void addButton(LinearLayout row,ImageButton b){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(45),dp(44));p.leftMargin=dp(5);row.addView(b,p);
    }
    private int availableTravel(){return Math.max(0,getResources().getDisplayMetrics().widthPixels-params.width-dp(12));}
    private void place(){
        params.x=dp(6)+Math.round(horizontal*availableTravel());
        if(sheet.isAttachedToWindow())windows.updateViewLayout(sheet,params);
    }
    private void draggable(View view){
        view.setOnTouchListener(new View.OnTouchListener(){
            private float down;
            private int origin;
            private boolean moved;
            @Override public boolean onTouch(View v,android.view.MotionEvent e){
                switch(e.getActionMasked()){
                    case android.view.MotionEvent.ACTION_DOWN:
                        down=e.getRawX();origin=params.x;moved=false;return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float delta=e.getRawX()-down;
                        if(Math.abs(delta)>android.view.ViewConfiguration.get(Overlay.this).getScaledTouchSlop())moved=true;
                        if(moved){
                            int range=availableTravel();
                            if(range>0) horizontal=Math.max(0,Math.min(1,(origin+delta-dp(6))/range));
                            place();
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if(moved)new Store(Overlay.this).prefs.edit().putFloat("overlayPosition",horizontal).apply();
                        else v.performClick();
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:return true;
                    default:return true;
                }
            }
        });
    }

    private void show() {
        store=new Store(this);
        windows=getSystemService(WindowManager.class);
        horizontal=new Store(this).prefs.getFloat("overlayPosition",.5f);
        sheet=column();
        sheet.setPadding(dp(8),dp(8),dp(8),dp(8));
        sheet.setBackground(IslandVisuals.glass(tint,dp(29),false));
        sheet.setElevation(dp(10));
        LinearLayout header=row();sheet.addView(header);
        cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(rounded(SURFACE,14));cover.setClipToOutline(true);
        cover.setImageResource(R.drawable.island);header.addView(cover,dp(44),dp(44));
        LinearLayout names=column();names.setPadding(dp(9),0,0,0);
        title=text("Craftify",15,TEXT);title.setTypeface(Typeface.create("sans-serif-medium",0));names.addView(title);
        subtitle=text("Нет активного трека",12,0xffbbc1ce);names.addView(subtitle);
        FrameLayout content=new FrameLayout(this);
        content.addView(names,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_VERTICAL));
        compactLyrics=new LyricStrip(this,true);compactLyrics.setPadding(dp(9),0,dp(5),0);
        content.addView(compactLyrics,new FrameLayout.LayoutParams(-1,-1));
        content.setOnClickListener(v->expand());
        header.addView(content,new LinearLayout.LayoutParams(0,dp(44),1));
        names.setContentDescription("Развернуть или свернуть плеер");
        names.setOnClickListener(v->expand());
        cover.setOnClickListener(v->expand());
        pulse=new IslandVisuals.Pulse(this);header.addView(pulse,dp(34),dp(32));pulse.setOnClickListener(v->expand());
        detail=button(R.drawable.ic_down,"Развернуть",false,this::expand);addButton(header,detail);
        detail.setVisibility(View.GONE);

        panel=column();panel.setVisibility(View.GONE);sheet.addView(panel);
        lyrics=new LyricStrip(this,false);lyrics.setPadding(dp(8),0,dp(8),0);panel.addView(lyrics,new LinearLayout.LayoutParams(-1,dp(76)));
        progress=new IslandVisuals.Dots(this);progress.setMax(1000);progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setThumbTintList(ColorStateList.valueOf(ACCENT));
        panel.addView(progress,new LinearLayout.LayoutParams(-1,dp(42)));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b,int p,boolean user) {}
            @Override public void onStartTrackingTouch(SeekBar b) {dragging=true;}
            @Override public void onStopTrackingTouch(SeekBar b) {
                long ms=duration*b.getProgress()/1000;
                compactLyrics.resetProgress();lyrics.resetProgress();
                if(remote!=null) remote.getTransportControls().seekTo(ms);
                else if(local!=null) local.seek(ms);
                dragging=false;
            }
        });
        clock=text("",12,0xffbbc1ce);clock.setGravity(Gravity.CENTER);panel.addView(clock);
        controls=row();controls.setGravity(Gravity.CENTER);panel.addView(controls);
        prev=button(R.drawable.ic_previous,"Предыдущий трек",false,()->skip(false));addButton(controls,prev);
        toggle=button(R.drawable.ic_play,"Играть или пауза",true,this::toggle);addButton(controls,toggle);
        next=button(R.drawable.ic_next,"Следующий трек",false,()->skip(true));addButton(controls,next);
        addButton(controls,button(R.drawable.ic_expand,"Текст на весь экран",false,this::openFullPlayer));
        addButton(controls,button(R.drawable.ic_close,"Выключить плавающий плеер",false,()->{
            new Store(this).prefs.edit().putBoolean("overlay",false).apply();stopSelf();
        }));

        params=new WindowManager.LayoutParams(width(false),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;
        params.y=dp(8);
        place();windows.addView(sheet,params);
        draggable(cover);draggable(content);draggable(pulse);draggable(names);
    }

    private void openFullPlayer(){
        Intent screen=new Intent(this,Main.class).setAction(Main.OPEN_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(remote!=null)screen.putExtra("session",remote.getSessionToken());
        if(expanded)expand();
        startActivity(screen);
    }
    private int width(boolean wide){return Math.min(dp(wide?390:284),getResources().getDisplayMetrics().widthPixels-dp(24));}
    private void expand(){
        expanded=!expanded;
        if(resize!=null)resize.cancel();
        detail.setImageResource(expanded?R.drawable.ic_up:R.drawable.ic_down);detail.setContentDescription(expanded?"Свернуть":"Развернуть");
        detail.setVisibility(expanded?View.VISIBLE:View.GONE);
        sheet.setBackground(IslandVisuals.glass(tint,dp(expanded?33:29),expanded));
        if(expanded){
            panel.setVisibility(View.VISIBLE);panel.setAlpha(0f);
            panel.setTranslationY(-dp(6));panel.animate().alpha(1).translationY(0).setDuration(220).start();
        } else {
            panel.animate().alpha(0).translationY(-dp(6)).setDuration(130).withEndAction(()->{
                if(!expanded)panel.setVisibility(View.GONE);
            }).start();
        }
        if(!new Store(this).prefs.getBoolean("motion",true)){
            panel.animate().cancel();panel.setAlpha(1f);panel.setTranslationY(0f);
            panel.setVisibility(expanded?View.VISIBLE:View.GONE);
            params.width=width(expanded);place();update();return;
        }
        resize=android.animation.ValueAnimator.ofInt(params.width,width(expanded));
        resize.setDuration(260);
        resize.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        resize.addUpdateListener(animation->{
            if(sheet.isAttachedToWindow()){
                params.width=(int)animation.getAnimatedValue();
                place();
            }
        });
        resize.start();
        update();
    }
    private void findLyrics(String key,String title,String artist,long length){
        int sources=store.lyricSources();
        String requestKey=key.isEmpty()?"":sources+"|"+key;
        if(requestKey.equals(lyricKey))return;
        lyricKey=requestKey;currentLyrics=lyricCache.get(requestKey);
        if(sources==0||title.isEmpty()||lyricCache.containsKey(requestKey))return;
        lyricWorker.execute(()->{
            Lyrics found=null;
            try {found=Lyrics.lookup(title,artist,length,sources);}catch(Exception ignored){}
            Lyrics result=found;
            ui.post(()->{
                if(sheet==null)return;
                if(lyricCache.size()>64)lyricCache.clear();
                lyricCache.put(requestKey,result);
                if(requestKey.equals(lyricKey)){currentLyrics=result;update();}
            });
        });
    }
    private static boolean active(PlaybackState p){return p!=null&&p.getState()==PlaybackState.STATE_PLAYING;}
    private void update(){
        Player own=Player.current;
        MediaController first=null,playing=null;
        for(MediaController session:External.sessions(this)){
            if(session.getMetadata()==null) continue;
            if(first==null) first=session;
            if(active(session.getPlaybackState())) {playing=session;break;}
        }
        local=own!=null && own.track()!=null && (own.playing() || playing==null)?own:null;
        remote=local==null?(playing==null?first:playing):null;
        duration=0;
        long position=0;
        boolean isPlaying=false;
        Bitmap image=null;
        String track="",artist="";
        PlaybackState state=null;
        if(local!=null) {
            Track t=local.track();
            track=t.title;artist=t.artist;
            duration=local.length();position=local.time();isPlaying=local.playing();image=local.cover;
        } else if(remote!=null) {
            MediaMetadata meta=remote.getMetadata();state=remote.getPlaybackState();
            if(meta!=null) {
                CharSequence name=meta.getDescription().getTitle();
                if(name!=null) track=name.toString();
                artist=Track.clean(meta.getString(MediaMetadata.METADATA_KEY_ARTIST));

                duration=meta.getLong(MediaMetadata.METADATA_KEY_DURATION);image=meta.getDescription().getIconBitmap();
            }
            isPlaying=active(state);
            if(state!=null) {
                position=state.getPosition();
                if(isPlaying && state.getLastPositionUpdateTime()>0) position+=(long)(
                    (SystemClock.elapsedRealtime()-state.getLastPositionUpdateTime())*state.getPlaybackSpeed());
            }
        }
        String key=track.isEmpty()?"":(local!=null?local.track().key():remote.getPackageName())+"\n"+track+"\n"+artist;
        findLyrics(key,track,artist,duration);
        Lyrics.Frame frame=currentLyrics==null?null:currentLyrics.frame(position,duration);
        if(image!=art){
            art=image;tint=IslandVisuals.accent(art);
            sheet.setBackground(IslandVisuals.glass(tint,dp(expanded?33:29),expanded));
            ((IslandVisuals.Dots)progress).tint=tint;
            cover.animate().cancel();cover.setAlpha(.5f);
            if(art==null)cover.setImageResource(R.drawable.island);else cover.setImageBitmap(art);
            cover.animate().alpha(1).setDuration(180).start();
        }
        boolean showLine=!expanded && frame!=null;
        compactLyrics.setVisibility(showLine?View.VISIBLE:View.INVISIBLE);
        float speed=isPlaying?(state==null?1:state.getPlaybackSpeed()):0;
        compactLyrics.display(frame,"",TEXT,speed);
        title.setVisibility(showLine?View.INVISIBLE:View.VISIBLE);
        subtitle.setVisibility(showLine?View.INVISIBLE:View.VISIBLE);
        title.setText(track.isEmpty()?"Craftify":track);
        subtitle.setText(artist.isEmpty()?(track.isEmpty()?"Нет активного трека":"Сейчас играет"):artist);
        lyrics.display(frame,track.isEmpty()?"Нет активного трека":"",TEXT,speed);
        lyrics.setVisibility(frame==null?View.GONE:View.VISIBLE);
        pulse.display(isPlaying,tint);
        if(!Boolean.valueOf(isPlaying).equals(toggle.getTag())){toggle.setTag(isPlaying);toggle.setImageResource(isPlaying?R.drawable.ic_pause:R.drawable.ic_play);toggle.setContentDescription(isPlaying?"Пауза":"Воспроизвести");}
        toggle.setEnabled(local!=null||remote!=null);
        long actions=state==null?0:state.getActions();
        prev.setEnabled(local!=null || (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS)!=0);
        next.setEnabled(local!=null || (actions & PlaybackState.ACTION_SKIP_TO_NEXT)!=0);
        progress.setEnabled(duration>0 && (local!=null || (actions & PlaybackState.ACTION_SEEK_TO)!=0));
        position=Math.max(0,duration>0?Math.min(position,duration):position);
        if(!dragging)progress.setProgress(duration>0?(int)(1000*position/duration):0);
        clock.setText(format(position)+"  /  "+format(duration));
    }
    private String format(long ms){long s=ms/1000;return String.format(java.util.Locale.ROOT,"%d:%02d",s/60,s%60);}
    private void toggle(){
        if(local!=null) {if(local.playing())local.pause();else local.play();}
        else if(remote!=null) {if(active(remote.getPlaybackState()))remote.getTransportControls().pause();else remote.getTransportControls().play();}
        update();
    }
    private void skip(boolean forward){
        if(local!=null){if(forward)local.step(1);else local.previous();}
        else if(remote!=null){if(forward)remote.getTransportControls().skipToNext();else remote.getTransportControls().skipToPrevious();}
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration changes){
        super.onConfigurationChanged(changes);
        if(params!=null){if(resize!=null)resize.cancel();params.width=width(expanded);place();}
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){
        ui.removeCallbacksAndMessages(null);
        lyricWorker.shutdownNow();if(resize!=null)resize.cancel();
        if(sheet!=null && sheet.isAttachedToWindow())windows.removeView(sheet);
        sheet=null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}










