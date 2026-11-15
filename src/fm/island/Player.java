package fm.island;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.*;
import android.media.session.*;
import android.os.*;
import android.widget.Toast;
import org.videolan.libvlc.*;
import java.util.*;

public final class Player extends Service {
    static Player current;
    static List<Track> pending;
    static int pendingIndex;
    static void open(Context c,List<Track> queue,int index) {
        pending=new ArrayList<>(queue);
        pendingIndex=index;
        c.startForegroundService(new Intent(c,Player.class).setAction("open"));
    }
    private List<Track> queue=Collections.emptyList();
    private int index;
    private final List<Integer> order=new ArrayList<>();
    private boolean shuffled,ended;
    private int repeat;
    private Store settings;
    private LibVLC library;
    private org.videolan.libvlc.MediaPlayer engine;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private Store.Meter meter;
    Bitmap cover;
    private android.content.res.AssetFileDescriptor audioFile;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            Track t=track();
            if(t!=null) meter.sample(t.key(),t.title,t.artist,playing());
            handler.postDelayed(this,1000);
        }
    };
    private final Set<Integer> connectedOutputs=new HashSet<>();
    private final AudioDeviceCallback audioDevices=new AudioDeviceCallback(){
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices){
            boolean added=false;
            for(AudioDeviceInfo device:devices){
                if(isExternalOutput(device)&&connectedOutputs.add(device.getId()))added=true;
            }
            if(added&&settings.prefs.getBoolean(Store.PAUSE_ON_CONNECT,false))pause();
        }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices){
            boolean removed=false;
            for(AudioDeviceInfo device:devices){
                if(connectedOutputs.remove(device.getId()))removed=true;
            }
            if(removed&&settings.prefs.getBoolean(Store.PAUSE_ON_DISCONNECT,true))pause();
        }
    };
    private static boolean isExternalOutput(AudioDeviceInfo device){
        if(!device.isSink())return false;
        switch(device.getType()){
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return true;
            default:return false;
        }
    }
    private final BroadcastReceiver unplug=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) {
            if(settings.prefs.getBoolean(Store.PAUSE_ON_DISCONNECT,true))pause();
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        current=this;
        settings=new Store(this);
        shuffled=settings.prefs.getBoolean("shuffle",false);
        repeat=settings.prefs.getInt("repeat",0);
        meter=new Store.Meter(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("music","Воспроизведение",NotificationManager.IMPORTANCE_LOW));
        audio=getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(change -> { if(change<0) pause(); }).build();
        IntentFilter filter=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(unplug,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(unplug,filter);
        session=new MediaSession(this,"Island");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay(){ play(); }
            @Override public void onPause(){ pause(); }
            @Override public void onSkipToNext(){ step(1); }
            @Override public void onSkipToPrevious(){ previous(); }
            @Override public void onSeekTo(long ms){ seek(ms); }
            @Override public void onStop(){ stopSelf(); }
        });
        session.setSessionActivity(activity());
        session.setActive(true);
        for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            if(isExternalOutput(device))connectedOutputs.add(device.getId());
        }
        audio.registerAudioDeviceCallback(audioDevices,handler);
        handler.post(tick);
    }
    @Override public int onStartCommand(Intent i,int flags,int id) {
        if(i==null) return START_NOT_STICKY;
        switch(i.getAction()==null?"":i.getAction()) {
            case "open":
                startForeground(10,notification());
                if(pending!=null) {
                    queue=pending;
                    pending=null;
                    buildOrder(pendingIndex);load(pendingIndex);
                }
                break;
            case "toggle": if(playing()) pause(); else play(); break;
            case "next": step(1); break;
            case "previous": previous(); break;
            case "close": stopSelf(); break;
        }
        return START_NOT_STICKY;
    }
    Track track(){ return index>=0 && index<queue.size()?queue.get(index):null; }
    boolean playing(){ return engine!=null && engine.isPlaying(); }
    long time(){ return engine==null?0:Math.max(0,engine.getTime()); }
    long length(){
        long ms=engine==null?0:engine.getLength();
        return ms>0?ms:track()==null?0:track().length;
    }
    List<Track> queue(){
        List<Track> result=new ArrayList<>();
        for(int item:order)result.add(queue.get(item));
        return result;
    }
    void removeTrack(String key){
        Track current=track();
        List<Track> remaining=new ArrayList<>(queue);
        if(!remaining.removeIf(item->item.key().equals(key)))return;
        queue=remaining;
        if(current!=null&&current.key().equals(key)){
            index=-1;
            releaseEngine();
            stopSelf();
        }else{
            index=queue.indexOf(current);
            buildOrder(index);
        }
    }
    void playQueueItem(int position){if(position>=0&&position<order.size())load(order.get(position));}
    boolean shuffled(){return shuffled;}
    int repeat(){return repeat;}
    void toggleShuffle(){
        shuffled=!shuffled;
        settings.prefs.edit().putBoolean("shuffle",shuffled).apply();
        buildOrder(index);
    }
    void cycleRepeat(){repeat=(repeat+1)%3;settings.prefs.edit().putInt("repeat",repeat).apply();}
    private void buildOrder(int selected){
        order.clear();
        for(int i=0;i<queue.size();i++)order.add(i);
        if(shuffled){
            order.remove(Integer.valueOf(selected));Collections.shuffle(order);
            if(selected>=0&&selected<queue.size())order.add(0,selected);
        }
    }
    boolean canSeek(){return engine!=null && engine.isSeekable();}
    void seek(long ms){ if(canSeek()) {engine.setTime(ms); publish();} }
    void previous(){ if(time()>3000) seek(0); else step(-1); }
    void step(int delta){
        int next=order.indexOf(index)+delta;
        if(order.isEmpty())return;
        if(repeat==2)next=Math.floorMod(next,order.size());
        if(next>=0&&next<order.size())load(order.get(next));
    }
    private void completed(){
        int next=order.indexOf(index)+1;
        if(repeat==1)load(index);
        else if(next<order.size())load(order.get(next));
        else if(repeat==2&&!order.isEmpty())load(order.get(0));
        else {ended=true;audio.abandonAudioFocusRequest(focus);publish();}
    }
    void play() {
        if(ended){load(index);return;}
        if(engine!=null && audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) engine.play();
    }
    void pause(){ if(engine!=null) engine.pause(); }
    void load(int at) {
        releaseEngine();
        ended=false;index=at;
        Track t=track();
        if(t==null) return;
        cover=null;
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,t.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST,t.artist).putLong(MediaMetadata.METADATA_KEY_DURATION,t.length).build());
        try {
            if(library==null) library=new LibVLC(this,new ArrayList<>(Arrays.asList("--no-video","--no-video-title-show")));
            org.videolan.libvlc.MediaPlayer next=new org.videolan.libvlc.MediaPlayer(library);
            engine=next;
            next.setEventListener(event -> {
                if(engine!=next) return;
                switch(event.type) {
                    case org.videolan.libvlc.MediaPlayer.Event.EndReached:
                        handler.post(() -> {if(engine==next)completed();});
                        break;
                    case org.videolan.libvlc.MediaPlayer.Event.EncounteredError: fail(); break;
                    case org.videolan.libvlc.MediaPlayer.Event.Playing:
                    case org.videolan.libvlc.MediaPlayer.Event.Paused:
                    case org.videolan.libvlc.MediaPlayer.Event.LengthChanged:
                        publish();
                        break;
                }
            });
            {
                audioFile=getContentResolver().openAssetFileDescriptor(t.uri,"r");
                if(audioFile==null) throw new java.io.IOException();
                Media media=new Media(library,audioFile);
                next.setMedia(media);
                media.release();
            }
            play();
            publish();
            new Thread(() -> readCover(t),"cover").start();
        } catch(Exception | LinkageError e) {
            android.util.Log.e("Island","Playback setup",e);
            fail();
        }
    }
    private void readCover(Track t) {
        MediaMetadataRetriever r=new MediaMetadataRetriever();
        try {
            r.setDataSource(this,t.uri);
            byte[] bytes=r.getEmbeddedPicture();
            if(bytes==null) return;
            BitmapFactory.Options opts=new BitmapFactory.Options();
            opts.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
            opts.inSampleSize=Math.max(1,Math.max(opts.outWidth,opts.outHeight)/384);
            opts.inJustDecodeBounds=false;
            Bitmap found=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
            handler.post(() -> {if(track()==t && current==this) {cover=found; publish();}});
        } catch(Exception ignored) {}
        finally { try {r.release();} catch(Exception ignored) {} }
    }
    private void fail(){
        releaseEngine();
        audio.abandonAudioFocusRequest(focus);
        Toast.makeText(this,"Не удалось открыть аудио",Toast.LENGTH_LONG).show();
        publish();
    }
    private void publish() {
        Track t=track();
        if(t!=null) {
            MediaMetadata.Builder data=new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,t.artist).putLong(MediaMetadata.METADATA_KEY_DURATION,length());
            if(cover!=null) data.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,cover);
            session.setMetadata(data.build());
        }
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE|
            PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|
            PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_STOP)
            .setState(playing()?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,time(),playing()?1:0).build());
        getSystemService(NotificationManager.class).notify(10,notification());
    }
    private PendingIntent activity(){
        return PendingIntent.getActivity(this,0,new Intent(this,Main.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE);
    }
    private PendingIntent command(String action){
        return PendingIntent.getService(this,0,new Intent(this,Player.class).setAction(action),PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification(){
        Track t=track();
        return new Notification.Builder(this,"music").setSmallIcon(R.drawable.island).setContentIntent(activity())
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setLargeIcon(cover)
            .setContentTitle(t==null?"Craftify":t.title).setContentText(t==null?"Подготовка":t.artist)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous,"Назад",command("previous")).build())
            .addAction(new Notification.Action.Builder(playing()?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,
                playing()?"Пауза":"Играть",command("toggle")).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next,"Вперёд",command("next")).build())
            .setDeleteIntent(command("close"))
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2)).build();
    }
    private void releaseEngine(){
        if(engine==null) return;
        engine.setEventListener(null);
        engine.stop();
        engine.release();
        engine=null;
        if(audioFile!=null) { try {audioFile.close();} catch(java.io.IOException ignored) {} audioFile=null; }
    }
    @Override public android.os.IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){
        handler.removeCallbacksAndMessages(null);
        audio.unregisterAudioDeviceCallback(audioDevices);
        unregisterReceiver(unplug);
        releaseEngine();
        if(library!=null) library.release();
        audio.abandonAudioFocusRequest(focus);
        session.release();
        current=null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}





