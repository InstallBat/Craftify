package fm.island;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.*;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class Main extends Activity {
    static boolean fullPlayerVisible;
    static final String OPEN_PLAYER="fm.island.OPEN_PLAYER";
    private int BG,PANEL,TEXT,MUTED,ACCENT;
    private boolean light;
    private ImageButton shuffleButton,repeatButton;
    private SeekBar volumeBar;
    private android.media.AudioManager audio;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService network=Executors.newFixedThreadPool(2);
    private final Map<String,Lyrics> lyricCache=new HashMap<>();
    private List<Track> tracks=new ArrayList<>();
    private Store store;
    private LibraryCache libraryCache;
    private static final long SCAN_INTERVAL=5*60*1000L;
    private LinearLayout root,body,island;
    private FrameLayout screen;
    private View pageContent;
    private ImageView outgoingPage;
    private TextView now,by;
    private ImageButton play,expandedPlay;
    private LyricStrip miniLyrics;
    private KaraokeView karaoke;
    private long trackLength;
    private IslandVisuals.Pulse musicPulse;
    private int coverTint=IslandVisuals.FALLBACK;
    private ImageView art,largeArt;
    private SeekBar seek;
    private TextView time, expandedTitle, expandedArtist;
    private String manualTarget, pendingManual, pendingDelete;
    private String page="library",listName="",query="",lyricKey="";
    private static final String[] TABS={"library","favorites","lists","stats"};
    private String playerReturnPage="library";
    private ImageButton overlayButton;
    private float swipeX,swipeY;
    private boolean watchSwipe;
    private int sort;
    private boolean reversed,drag,loading;
    private Lyrics lyric;
    private MediaSession.Token remote;
    private int scanGeneration;
    private boolean scanning, scanAgain, pendingOverlay, resumed;
    private TextView storageButton;
    private final Runnable rescan=this::scan;
    private final android.database.ContentObserver mediaChanges=new android.database.ContentObserver(ui) {
        @Override public void onChange(boolean selfChange){
            ui.removeCallbacks(rescan);
            ui.postDelayed(rescan,900);
        }
    };
    private final Runnable ticker=new Runnable() {
        @Override public void run(){ update(); ui.postDelayed(this,75); }
    };
    @Override public void onCreate(Bundle b){
        store=new Store(this);
        String theme=store.prefs.getString("theme","dark");
        light=theme.equals("light")||theme.equals("system")&&
            (getResources().getConfiguration().uiMode&48)==16;
        if(light)setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        palette();
        super.onCreate(b);
        audio=getSystemService(android.media.AudioManager.class);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        if(b!=null){page=b.getString("page","library");listName=b.getString("list","");remote=b.getParcelable("remote");pendingManual=b.getString("manualFile");pendingDelete=b.getString("deleteTrack");}
        if(b!=null)playerReturnPage=b.getString("playerReturnPage","library");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(light?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0);
        sort=store.prefs.getInt("sort",0);
        reversed=store.prefs.getBoolean("reverse",false);
        libraryCache=new LibraryCache(this);
        render();
        io.execute(()->{
            List<Track> saved=libraryCache.load();
            boolean full=LibraryScan.fullAccess(this),media=allowed();
            Set<String> imports=store.set("imports");
            List<Track> accessible=new ArrayList<>();
            for(Track t:saved){
                if(imports.contains(t.key()) || ("file".equals(t.uri.getScheme())?full:media||full))accessible.add(t);
            }
            ui.post(()->{
                if(isDestroyed())return;
                tracks=accessible;
                if(isTracks())refreshTracks();
            });
        });
        openIntent(getIntent());
    }
    @Override protected void onSaveInstanceState(Bundle state){
        state.putString("page",page);state.putString("list",listName);state.putParcelable("remote",remote);
        state.putString("playerReturnPage",playerReturnPage);
        state.putString("manualFile",pendingManual);state.putString("deleteTrack",pendingDelete);
        super.onSaveInstanceState(state);
    }
    private void palette(){
        BG=light?0xfff8f7fc:0xff0c0c10;
        PANEL=light?0xffece8f3:0xff1b1b23;
        TEXT=light?0xff221c2e:0xfff6f7fc;
        MUTED=light?0xff61596e:0xffbbc1ce;
        ACCENT=light?0xff6546a5:0xffc9b8ff;
    }
    @Override protected void onResume(){
        super.onResume();resumed=true;
        fullPlayerVisible=page.equals("now");
        try {
            getContentResolver().registerContentObserver(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,true,mediaChanges);
        } catch(SecurityException ignored) {}
        scan();
        if(pendingOverlay){
            pendingOverlay=false;
            if(Settings.canDrawOverlays(this))setOverlay(true);
        } else if(store.prefs.getBoolean("overlay",false)&&Settings.canDrawOverlays(this)) {
            startForegroundService(new Intent(this,Overlay.class));
        }
        if(page.equals("apps"))go("apps");
        updateOverlayButton();
        ui.removeCallbacks(ticker);ui.post(ticker);
    }
    @Override protected void onPause(){
        resumed=false;fullPlayerVisible=false;
        getContentResolver().unregisterContentObserver(mediaChanges);
        ui.removeCallbacks(rescan);ui.removeCallbacks(ticker);super.onPause();
    }
    @Override protected void onDestroy(){
        ui.removeCallbacksAndMessages(null);io.shutdownNow();network.shutdownNow();super.onDestroy();
    }
    @Override public void onBackPressed(){
        if(page.equals("now"))closePlayer();
        else if(page.equals("list"))go("lists");
        else if(!page.equals("library"))go("library");
        else super.onBackPressed();
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);openIntent(i);}
    private void openIntent(Intent i){
        if(Intent.ACTION_VIEW.equals(i.getAction()) && i.getData()!=null)importUri(i.getData(),false);
        else if(OPEN_PLAYER.equals(i.getAction())){
            remote=i.getParcelableExtra("session");go("now");
        }
    }
    private String permission(){return Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_AUDIO:Manifest.permission.READ_EXTERNAL_STORAGE;}
    private boolean allowed(){return checkSelfPermission(permission())==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    private void storageSettings() {
        if(Build.VERSION.SDK_INT>=30 && !LibraryScan.fullAccess(this)) {
            try {startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())));}
            catch(ActivityNotFoundException e){startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}
        } else if(!allowed() && !LibraryScan.fullAccess(this)) requestPermissions(new String[]{permission()},1);
        else scan();
    }
    private void setOverlay(boolean enabled) {
        if(enabled && !Settings.canDrawOverlays(this)) {
            pendingOverlay=true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
            return;
        }
        store.prefs.edit().putBoolean("overlay",enabled).apply();
        Intent service=new Intent(this,Overlay.class);
        if(enabled) {
            startForegroundService(service);
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2);
        } else stopService(service);
        updateOverlayButton();
    }
    private void scan(){
        if(scanning){scanAgain=true;return;}
        scanning=true;
        ui.removeCallbacks(rescan);
        if(count!=null && tracks.isEmpty())count.setText("Поиск аудио…");
        int generation=++scanGeneration;
        io.execute(() -> {
            List<Track> cached=libraryCache.load();
            Map<String,Track> previous=new HashMap<>();
            for(Track track:cached)previous.put(track.key(),track);
            Map<String,Track> found=new LinkedHashMap<>();
            Map<String,Track> indexed=new HashMap<>();
            boolean full=LibraryScan.fullAccess(this);
            boolean complete=false;
            try {
                if(allowed() || full) for(Track t:Track.scan(this)) {
                    found.put(t.key(),t);
                    if(t.path!=null) indexed.put(LibraryScan.canonical(new java.io.File(t.path)),t);
                }
                if(full)LibraryScan.walk(this,indexed,found,previous);
                for(String key:store.set("imports")) {
                    Uri uri=Uri.parse(key);
                    if(!LibraryScan.excludedUri(this,uri))
                        found.computeIfAbsent(key,k -> previous.containsKey(k)?previous.get(k):Track.open(this,uri));
                }
                complete=!Thread.currentThread().isInterrupted();
            } catch(RuntimeException error) {
                android.util.Log.w("Island","Library scan",error);
            }
            List<Track> result=new ArrayList<>(found.values());
            boolean success=complete;
            if(success&&(full||allowed())&&!LibraryCache.same(cached,result)){
                try{libraryCache.save(result);}
                catch(java.io.IOException | RuntimeException error){android.util.Log.w("Craftify","Library cache",error);}
            }
            ui.post(() -> {
                if(isDestroyed() || generation!=scanGeneration) return;
                scanning=false;
                boolean changed=success&&!LibraryCache.same(tracks,result);
                if(changed)tracks=result;
                if(storageButton!=null){boolean fullNow=LibraryScan.fullAccess(this);storageButton.setText(fullNow?"Обновить":"Найти всю музыку");storageButton.setTextColor(fullNow?TEXT:BG);storageButton.setBackground(shape(fullNow?PANEL:ACCENT,24));}
                if(isTracks()){if(changed)refreshTracks();else if(count!=null&&tracks.isEmpty())count.setText("Нет треков");}
                if(scanAgain){scanAgain=false;scan();}
                else if(resumed)ui.postDelayed(rescan,SCAN_INTERVAL);
            });
        });
    }
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] r){super.onRequestPermissionsResult(request,p,r);scan();}
    private void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        startActivityForResult(i,20);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==33){
            String target=pendingManual;pendingManual=null;
            if(target!=null&&result==RESULT_OK&&data!=null&&data.getData()!=null)
                readManualLyrics(target,data.getData());
            return;
        }
        if(request==34||request==35){
            String deleted=pendingDelete;pendingDelete=null;
            if(result==RESULT_OK&&deleted!=null){
                if(request==35){
                    try{
                        if(getContentResolver().delete(Uri.parse(deleted),null,null)>0)finishDeletion(deleted);
                        else showDeleteError();
                    }catch(RuntimeException denied){showDeleteError();}
                }
                else finishDeletion(deleted);
            }
            return;
        }
        if((request==31||request==32)&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri image=data.getData();
            try{
                getContentResolver().takePersistableUriPermission(image,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                store.prefs.edit().putString((request==31?"player":"lyrics")+"Backdrop",image.toString()).apply();
                go("apps");backdropSettings(request==31?"player":"lyrics");
            }catch(SecurityException error){Toast.makeText(this,"Не удалось открыть изображение",Toast.LENGTH_SHORT).show();}
            return;
        }
        if(request!=20 || result!=RESULT_OK || data==null) return;
        if(data.getClipData()==null) {if(data.getData()!=null) importUri(data.getData(),true);return;}
        for(int i=0;i<data.getClipData().getItemCount();i++) {
            Uri uri=data.getClipData().getItemAt(i).getUri();
            if(LibraryScan.excludedUri(this,uri))continue;
            try {getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);store.add("imports",uri.toString());}
            catch(SecurityException ignored){}
        }
        scan();
    }
    private void importUri(Uri uri,boolean persist){
        if(LibraryScan.excludedUri(this,uri)){Toast.makeText(this,"Папка исключена из медиатеки",0).show();return;}
        if(persist) try {
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            store.add("imports",uri.toString());
            scan();
        } catch(SecurityException ignored){}
        io.execute(() -> {
            Track t=Track.open(this,uri);
            ui.post(() -> {if(!isDestroyed() && !isFinishing()) start(Collections.singletonList(t),0,true);});
        });
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;
    }
    private TextView label(String s,int size,int color){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);
        v.setFontFeatureSettings("kern");return v;
    }
    private TextView heading(String s,int size){TextView v=label(s,size,TEXT);v.setTypeface(Typeface.create("sans-serif-medium",0));return v;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    private void gap(LinearLayout l,int height){l.addView(new View(this),1,dp(height));}
    private TextView chip(String s,Runnable click){return chip(s,click,false);}
    private TextView chip(String s,Runnable click,boolean active){
        TextView t=heading(s,13);t.setGravity(Gravity.CENTER);pad(t,12,8);t.setMinHeight(dp(46));
        t.setTextColor(active?BG:TEXT);t.setBackground(shape(active?ACCENT:PANEL,24));
        t.setOnClickListener(v -> click.run());return t;
    }
    private void chipIn(LinearLayout r,View t){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(2),0,dp(2),0);r.addView(t,p);}
    private ImageButton icon(int resource,String description,Runnable action){
        ImageButton b=new ImageButton(this);
        b.setContentDescription(description);b.setImageResource(resource);
        b.setImageTintList(ColorStateList.valueOf(TEXT));b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33665888),shape(PANEL,23),null));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(46),dp(46)));
        b.setMinimumHeight(dp(46));b.setMinimumWidth(dp(46));b.setOnClickListener(v->action.run());
        return b;
    }
    private ImageView artwork(int size){
        ImageView i=new ImageView(this);i.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));
        i.setScaleType(ImageView.ScaleType.CENTER_CROP);i.setBackground(shape(PANEL,16));
        i.setClipToOutline(true);i.setImageResource(R.drawable.island);return i;
    }
    private void closePlayer(){go(playerReturnPage);}

    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            swipeX=event.getX();swipeY=event.getY();
            watchSwipe=(tabIndex(page)>=0||page.equals("now"))&&!touches(seek,event)&&!touches(volumeBar,event);
        }
        if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_CANCEL)watchSwipe=false;
        if(watchSwipe&&(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)){
            float dx=event.getX()-swipeX,dy=event.getY()-swipeY;
            int slop=ViewConfiguration.get(this).getScaledTouchSlop();
            if(Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx))watchSwipe=false;
            if(action==MotionEvent.ACTION_UP&&watchSwipe&&Math.abs(dx)>Math.abs(dy)*1.5f){
                boolean player=page.equals("now");
                float threshold=player?Math.max(dp(120),root.getWidth()*.34f):dp(56);
                int target=tabIndex(page)+(dx<0?1:-1);
                if(Math.abs(dx)>=threshold&&(player||target>=0&&target<TABS.length)){
                    watchSwipe=false;
                    MotionEvent cancel=MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    if(player)closePlayer();else go(TABS[target]);
                    return true;
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean touches(View view,MotionEvent event){
        if(view==null||!view.isShown())return false;
        int[] location=new int[2];
        view.getLocationOnScreen(location);
        float x=event.getRawX()-location[0],y=event.getRawY()-location[1];
        return x>=0&&x<view.getWidth()&&y>=0&&y<view.getHeight();
    }

    private boolean overlayEnabled(){
        return store.prefs.getBoolean("overlay",false)&&Settings.canDrawOverlays(this);
    }
    private void updateOverlayButton(){
        if(overlayButton==null)return;
        boolean enabled=overlayEnabled();
        if(Boolean.valueOf(enabled).equals(overlayButton.getTag()))return;
        overlayButton.setTag(enabled);
        overlayButton.setImageTintList(ColorStateList.valueOf(enabled?BG:ACCENT));
        overlayButton.setBackground(shape(enabled?ACCENT:PANEL,28));
        overlayButton.setContentDescription(enabled?"Выключить плавающий плеер":"Включить плавающий плеер");
        overlayButton.setSelected(enabled);
    }

    private void go(String name){
        boolean transition=!page.equals(name);
        boolean collapse=transition&&page.equals("now");
        int previous=tabIndex(page),current=tabIndex(name);
        int direction=transition&&previous>=0&&current>=0?Integer.signum(current-previous):0;
        ImageView outgoing=collapse||direction!=0?snapshotPage():null;
        if(transition&&name.equals("now"))playerReturnPage=page;
        page=name;fullPlayerVisible=page.equals("now");render(transition?previous:-1);
        if(outgoing!=null){
            outgoingPage=outgoing;
            screen.addView(outgoing);
            if(collapse)animateCollapse(outgoing);
            else animateTabs(outgoing,direction);
        }else if(transition){
            pageContent.setAlpha(0f);
            pageContent.setTranslationY(dp(14));
            pageContent.animate().alpha(1f).translationY(0f).setDuration(280)
                .setInterpolator(pageInterpolator()).start();
        }
    }

    private android.view.animation.Interpolator pageInterpolator(){
        return new android.view.animation.PathInterpolator(.22f,.72f,.24f,1f);
    }

    private ImageView snapshotPage(){
        if(pageContent==null||pageContent.getWidth()==0||pageContent.getHeight()==0)return null;
        int width=pageContent.getWidth(),height=pageContent.getHeight();
        float scale=Math.min(1f,1080f/width);
        Bitmap snapshot=Bitmap.createBitmap(Math.max(1,Math.round(width*scale)),
            Math.max(1,Math.round(height*scale)),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(snapshot);
        canvas.scale(scale,scale);
        try{
            pageContent.draw(canvas);
        }catch(IllegalArgumentException unsupportedBitmap){
            snapshot.recycle();
            return null;
        }
        ImageView image=new ImageView(this);
        image.setImageBitmap(snapshot);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int[] contentPosition=new int[2],screenPosition=new int[2];
        pageContent.getLocationInWindow(contentPosition);
        screen.getLocationInWindow(screenPosition);
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(width,height);
        params.leftMargin=contentPosition[0]-screenPosition[0];
        params.topMargin=contentPosition[1]-screenPosition[1];
        image.setLayoutParams(params);
        return image;
    }

    private void animateTabs(ImageView outgoing,int direction){
        float distance=outgoing.getLayoutParams().width*.58f;
        pageContent.setTranslationX(distance*direction);
        pageContent.setAlpha(.30f);
        pageContent.animate().translationX(0).alpha(1).setDuration(370)
            .setInterpolator(pageInterpolator()).start();
        outgoing.animate().translationX(-distance*direction).alpha(0).setDuration(370)
            .setInterpolator(pageInterpolator()).withEndAction(()->removeSnapshot(outgoing)).start();
    }

    private void animateCollapse(ImageView outgoing){
        View miniPlayer=island;
        miniPlayer.setAlpha(0);
        pageContent.setAlpha(.55f);
        pageContent.animate().alpha(1).setDuration(330).setInterpolator(pageInterpolator()).start();
        miniPlayer.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){
            @Override public void onLayoutChange(View view,int l,int t,int r,int b,int ol,int ot,int or,int ob){
                miniPlayer.removeOnLayoutChangeListener(this);
                if(outgoingPage!=outgoing)return;
                FrameLayout.LayoutParams bounds=(FrameLayout.LayoutParams)outgoing.getLayoutParams();
                float height=bounds.height;
                float targetCenter=miniPlayer.getTop()+miniPlayer.getHeight()/2f;
                outgoing.animate().translationY(targetCenter-bounds.topMargin-height/2f)
                    .scaleX(.88f).scaleY(Math.max(.06f,miniPlayer.getHeight()/height))
                    .alpha(0).setDuration(350).setInterpolator(pageInterpolator())
                    .withEndAction(()->removeSnapshot(outgoing)).start();
                miniPlayer.animate().alpha(1).setStartDelay(175).setDuration(175)
                    .setInterpolator(pageInterpolator()).start();
            }
        });
    }

    private void removeSnapshot(ImageView snapshot){
        if(outgoingPage!=snapshot)return;
        outgoingPage=null;
        snapshot.animate().cancel();
        screen.removeView(snapshot);
        snapshot.setImageDrawable(null);
    }

    private ListView trackList;
    private TextView count;
    private int tabIndex(String name){
        switch(name){
            case "library":return 0;
            case "favorites":return 1;
            case "list":
            case "lists":return 2;
            case "stats":return 3;
            default:return -1;
        }
    }
    private void render(){render(-1);}
    private void render(int previousTab){
        if(screen==null){
            screen=new FrameLayout(this);
            setContentView(screen);
        }
        if(outgoingPage!=null)removeSnapshot(outgoingPage);
        screen.removeAllViews();
        root=column();root.setBackgroundColor(BG);pad(root,18,10);
        screen.addView(root,new FrameLayout.LayoutParams(-1,-1));
        applyBackdrop(root,"player");
        LinearLayout header=row();
        TextView brand=heading("Craftify",28);brand.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD_ITALIC));brand.setLetterSpacing(-.045f);brand.setTextColor(ACCENT);
        header.addView(brand,new LinearLayout.LayoutParams(0,dp(46),1));brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton settings=icon(R.drawable.ic_settings,"Настройки",()->go("apps"));header.addView(settings);
        if(!page.equals("now")){root.addView(header);gap(root,10);}
        body=column();trackList=null;count=null;storageButton=null;
        if(isTracks()||page.equals("now")){
            pageContent=body;
            root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        }
        else {
            ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);
            pageContent=scroll;scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        }
        island=row();pad(island,8,5);island.setBackground(IslandVisuals.glass(coverTint,dp(40),false));
        art=artwork(34);island.addView(art);
        LinearLayout names=column();pad(names,10,0);
        now=heading("Плеер",14);now.setTextColor(Color.WHITE);now.setSingleLine(true);now.setEllipsize(TextUtils.TruncateAt.END);
        by=label("Выберите трек",11,0xffbbc1ce);by.setSingleLine(true);by.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(now);names.addView(by);
        FrameLayout compactContent=new FrameLayout(this);
        compactContent.addView(names,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_VERTICAL));
        miniLyrics=new LyricStrip(this,true);miniLyrics.setPadding(dp(9),0,dp(5),0);
        compactContent.addView(miniLyrics,new FrameLayout.LayoutParams(-1,-1));
        island.addView(compactContent,new LinearLayout.LayoutParams(0,dp(37),1));
        musicPulse=new IslandVisuals.Pulse(this);island.addView(musicPulse,dp(32),dp(33));
        play=icon(R.drawable.ic_play,"Воспроизвести",this::toggle);play.setImageTintList(ColorStateList.valueOf(Color.WHITE));play.setBackground(shape(0xff25212e,23));island.addView(play,dp(45),dp(45));
        island.setOnClickListener(v -> go("now"));
        if(!page.equals("now")){root.addView(island);gap(root,9);}
        LinearLayout nav=row();nav.setBackground(shape(PANEL,22));pad(nav,3,3);
        navItem(nav,R.drawable.ic_music,"Треки","library",page.equals("library"));
        navItem(nav,R.drawable.ic_heart,"Избранное","favorites",page.equals("favorites"));
        nav.addView(new View(this),dp(56),1);
        navItem(nav,R.drawable.ic_list,"Плейлисты","lists",page.equals("lists")||page.equals("list"));
        navItem(nav,R.drawable.ic_stats,"Статистика","stats",page.equals("stats"));
        overlayButton=null;
        if(!page.equals("now")){
            FrameLayout rail=new FrameLayout(this);
            View navBackground=new View(this);navBackground.setBackground(shape(PANEL,22));
            FrameLayout.LayoutParams backgroundParams=new FrameLayout.LayoutParams(-1,dp(59),Gravity.BOTTOM);
            rail.addView(navBackground,backgroundParams);
            nav.setBackground(null);
            int selected=tabIndex(page);
            if(selected>=0) {
                View indicator=new View(this);
                indicator.setBackground(shape(light?0xffded3f4:0xff2c2540,18));
                indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                rail.addView(indicator,new FrameLayout.LayoutParams(0,dp(53)));
                rail.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
                    if(r-l==or-ol)return;
                    float cell=(r-l-dp(62))/4f;
                    FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)indicator.getLayoutParams();
                    p.width=Math.round(cell)-dp(4);p.topMargin=dp(13);indicator.setLayoutParams(p);
                    float target=dp(5)+cell*selected+(selected>=2?dp(56):0);
                    indicator.animate().cancel();
                    if(previousTab>=0&&previousTab!=selected&&or==ol){
                        indicator.setTranslationX(dp(5)+cell*previousTab+(previousTab>=2?dp(56):0));
                        indicator.animate().translationX(target).setDuration(340)
                            .setInterpolator(new android.view.animation.PathInterpolator(.22f,1f,.36f,1f)).start();
                    }else indicator.setTranslationX(target);
                });
            }
            FrameLayout.LayoutParams navParams=new FrameLayout.LayoutParams(-1,dp(59),Gravity.BOTTOM);
            rail.addView(nav,navParams);
            overlayButton=icon(R.drawable.ic_floating,"Плавающий плеер",()->setOverlay(!overlayEnabled()));
            overlayButton.setElevation(dp(4));
            rail.addView(overlayButton,new FrameLayout.LayoutParams(dp(54),dp(54),Gravity.TOP|Gravity.CENTER_HORIZONTAL));
            updateOverlayButton();
            root.addView(rail,new LinearLayout.LayoutParams(-1,dp(69)));
        }
        seek=null;karaoke=null;drag=false;volumeBar=null;shuffleButton=null;repeatButton=null;modePlayer=null;largeArt=null;expandedTitle=null;expandedArtist=null;expandedPlay=null;
        island.setVisibility(page.equals("now")?View.GONE:View.VISIBLE);
        if(isTracks()) fillTracks();
        else if(page.equals("now")) nowPage();
        else if(page.equals("lists")) listsPage();
        else if(page.equals("stats")) statsPage();
        else if(page.equals("apps")) appsPage();
        update();
    }
    private void navItem(LinearLayout nav,int resource,String title,String destination,boolean active) {
        LinearLayout tab=column();tab.setGravity(Gravity.CENTER);

        ImageView symbol=new ImageView(this);symbol.setImageResource(resource);symbol.setImageTintList(ColorStateList.valueOf(active?ACCENT:MUTED));tab.addView(symbol,dp(22),dp(23));
        TextView name=label(title,10,active?ACCENT:MUTED);name.setGravity(Gravity.CENTER);tab.addView(name);
        tab.setContentDescription(title);tab.setSelected(active);
        tab.setForeground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x28665888),null,shape(Color.WHITE,18)));
        tab.setOnClickListener(v->{if(!page.equals(destination))go(destination);});
        nav.addView(tab,new LinearLayout.LayoutParams(0,dp(53),1));
    }
    private boolean isTracks(){return page.equals("library")||page.equals("favorites")||page.equals("list");}
    private void fillTracks(){
        if(trackList!=null){refreshTracks();return;}
        TextView title=heading(page.equals("favorites")?"Избранное":page.equals("list")?listName:"Медиатека",37);
        title.setLetterSpacing(-.035f);body.addView(title);gap(body,8);
        if(page.equals("library")){
            LinearLayout tools=row();
            chipIn(tools,chip("Добавить файлы",this::pick));
            storageButton=chip(LibraryScan.fullAccess(this)?"Обновить":"Найти всю музыку",this::storageSettings,!LibraryScan.fullAccess(this));
            chipIn(tools,storageButton);
            body.addView(tools);gap(body,10);
            EditText search=new EditText(this);search.setSingleLine(true);search.setTextSize(15);
            search.setHint("Поиск треков, артистов и альбомов");search.setText(query);pad(search,15,6);
            search.setTextColor(TEXT);search.setHintTextColor(MUTED);search.setBackground(shape(PANEL,16));
            body.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
            search.addTextChangedListener(new TextWatcher(){
                public void beforeTextChanged(CharSequence s,int a,int c,int f){}
                public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();refreshTracks();}
                public void afterTextChanged(Editable e){}
            });
        }
        gap(body,8);
        LinearLayout filters=row();count=label("",13,MUTED);
        filters.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        filters.addView(chip("Сортировка",this::sort));body.addView(filters);
        trackList=new ListView(this);
        trackList.setDivider(new android.graphics.drawable.ColorDrawable(0xff282d39));trackList.setDividerHeight(dp(1));
        trackList.setVerticalScrollBarEnabled(false);
        body.addView(trackList,new LinearLayout.LayoutParams(-1,0,1));
        root.setFocusableInTouchMode(true);root.requestFocus();
        refreshTracks();
    }
    private static final String[] SORT={"Название","Исполнитель","Альбом","Длительность","Дата добавления","Прослушивания"};
    private void sort(){
        String[] choices=new String[SORT.length*2];
        for(int i=0;i<SORT.length;i++){choices[2*i]=SORT[i]+" ↑";choices[2*i+1]=SORT[i]+" ↓";}
        new AlertDialog.Builder(this).setTitle("Сортировка").setSingleChoiceItems(choices,sort*2+(reversed?1:0),(d,n)->{
            sort=n/2;reversed=n%2==1;store.prefs.edit().putInt("sort",sort).putBoolean("reverse",reversed).apply();
            d.dismiss();fillTracks();
        }).show();
    }
    private void refreshTracks(){
        if(trackList==null)return;
        Set<String> filter=page.equals("favorites")?store.set("favorites"):page.equals("list")?store.set("list:"+listName):null;
        List<Track> visible=new ArrayList<>();
        String needle=page.equals("library")?query.trim().toLowerCase(Locale.ROOT):"";
        for(Track t:tracks) if((filter==null || filter.contains(t.key())) &&
            (t.title+" "+t.artist+" "+t.album).toLowerCase(Locale.ROOT).contains(needle)) visible.add(t);
        Comparator<Track> order;
        switch(sort){
            case 1: order=Comparator.comparing(t->t.artist,String.CASE_INSENSITIVE_ORDER);break;
            case 2: order=Comparator.comparing(t->t.album,String.CASE_INSENSITIVE_ORDER);break;
            case 3: order=Comparator.comparingLong(t->t.length);break;
            case 4: order=Comparator.comparingLong(t->t.added);break;
            case 5: JSONObject stats=store.stats();order=Comparator.comparingInt(t->stats.optJSONObject(t.key())==null?0:stats.optJSONObject(t.key()).optInt("plays"));break;
            default: order=Comparator.comparing(t->t.title,String.CASE_INSENSITIVE_ORDER);
        }
        visible.sort((reversed?order.reversed():order).thenComparing(t->t.title,String.CASE_INSENSITIVE_ORDER));
        count.setText(visible.isEmpty()?(scanning?"Поиск аудио…":"Нет треков"):visible.size()+" · "+SORT[sort]);
        Set<String> favorites=store.set("favorites");
        trackList.setAdapter(new BaseAdapter() {
            @Override public int getCount(){return visible.size();}
            @Override public Track getItem(int i){return visible.get(i);}
            @Override public long getItemId(int i){return i;}
            @Override public View getView(int i,View recycled,android.view.ViewGroup parent){
                TrackRow row=recycled instanceof TrackRow?(TrackRow)recycled:new TrackRow();
                Track t=getItem(i);
                row.name.setText(t.title);
                row.artist.setText((t.artist.isEmpty()?"Аудиофайл":t.artist)+(t.length>0?"  ·  "+clock(t.length):""));
                row.favorite.showFavorite(favorites.contains(t.key()));
                row.favorite.setOnClickListener(v->{
                    store.toggle("favorites",t.key());
                    boolean active=!favorites.remove(t.key());
                    if(active)favorites.add(t.key());
                    row.favorite.animateFavorite(active);
                    if(page.equals("favorites")&&!active){
                        ui.postDelayed(()->{
                            if(page.equals("favorites")&&!favorites.contains(t.key()))refreshTracks();
                        },FavoriteButton.REMOVE_DURATION+32);
                    }
                });
                row.more.setOnClickListener(v->trackMenu(t));
                row.setOnClickListener(v->start(visible,i,store.prefs.getBoolean("openPlayerOnTrack",true)));
                row.setOnLongClickListener(v->{trackMenu(t);return true;});
                return row;
            }
        });
    }

    private final class TrackRow extends LinearLayout {
        final TextView name,artist;final FavoriteButton favorite;final ImageButton more;
        TrackRow(){
            super(Main.this);setGravity(Gravity.CENTER_VERTICAL);pad(this,3,8);
            ImageView glyph=new ImageView(Main.this);glyph.setImageResource(R.drawable.ic_music);glyph.setScaleType(ImageView.ScaleType.CENTER_INSIDE);glyph.setImageTintList(ColorStateList.valueOf(ACCENT));
            glyph.setBackground(shape(light?0xffe5d9f4:0xff2c2540,14));addView(glyph,dp(44),dp(44));
            LinearLayout info=column();pad(info,11,0);
            name=heading("",16);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);
            artist=label("",13,MUTED);artist.setSingleLine(true);artist.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(name);info.addView(artist);addView(info,new LinearLayout.LayoutParams(0,-2,1));
            favorite=new FavoriteButton(Main.this);
            addView(favorite,dp(44),dp(44));
            more=icon(R.drawable.ic_more,"Действия с треком",()->{});
            LinearLayout.LayoutParams menuParams=new LinearLayout.LayoutParams(dp(45),dp(45));
            menuParams.leftMargin=dp(6);
            addView(more,menuParams);
        }
    }
    private void trackMenu(Track t){
        String playlist=page.equals("list")?listName:null;
        List<String> choices=new ArrayList<>();choices.add("Добавить в плейлист");
        if(playlist!=null)choices.add("Убрать из плейлиста");
        choices.add("Удалить файл с устройства");
        new AlertDialog.Builder(this).setTitle(t.title).setItems(choices.toArray(new String[0]),(d,n)->{
            if(n==0)chooseList(t);
            else if(n==choices.size()-1)new AlertDialog.Builder(this)
                .setTitle("Удалить «"+t.title+"» с устройства?")
                .setMessage("Файл будет удалён из памяти устройства и из всех плейлистов Craftify.")
                .setPositiveButton("Удалить",(confirm,button)->deleteTrackFile(t.uri))
                .setNegativeButton("Отмена",null).show();
            else {store.remove("list:"+playlist,t.key());fillTracks();}
        }).show();
    }
    private void deleteTrackFile(Uri uri){
        try{
            boolean deleted;
            if("file".equals(uri.getScheme())){
                java.io.File file=new java.io.File(uri.getPath());
                deleted=file.delete();
                if(deleted)android.media.MediaScannerConnection.scanFile(this,new String[]{file.getPath()},null,null);
            }else if(android.provider.DocumentsContract.isDocumentUri(this,uri)){
                deleted=android.provider.DocumentsContract.deleteDocument(getContentResolver(),uri);
            }else{
                deleted=getContentResolver().delete(uri,null,null)>0;
            }
            if(deleted)finishDeletion(uri.toString());else showDeleteError();
        }catch(Exception error){
            try{
                if(android.os.Build.VERSION.SDK_INT>=30&&"media".equals(uri.getAuthority())){
                    PendingIntent consent=android.provider.MediaStore.createDeleteRequest(getContentResolver(),Collections.singletonList(uri));
                    pendingDelete=uri.toString();
                    startIntentSenderForResult(consent.getIntentSender(),34,null,0,0,0);
                }else if(android.os.Build.VERSION.SDK_INT>=29&&error instanceof android.app.RecoverableSecurityException&&pendingDelete==null){
                    pendingDelete=uri.toString();
                    startIntentSenderForResult(((android.app.RecoverableSecurityException)error).getUserAction().getActionIntent().getIntentSender(),35,null,0,0,0);
                }else showDeleteError();
            }catch(Exception denied){pendingDelete=null;showDeleteError();}
        }
    }
    private void showDeleteError(){Toast.makeText(this,"Android не разрешил удалить файл",Toast.LENGTH_SHORT).show();}
    private void finishDeletion(String key){
        Player player=Player.current;
        if(player!=null)player.removeTrack(key);
        store.removeTrack(key);
        List<Track> remaining=new ArrayList<>(tracks);
        remaining.removeIf(item->item.key().equals(key));
        tracks=remaining;
        lyricKey=null;
        if(isTracks())refreshTracks();
        scan();
    }
    private void chooseList(Track t){
        List<String> names=store.lists();names.add("Создать плейлист");
        new AlertDialog.Builder(this).setTitle("Добавить в плейлист").setItems(names.toArray(new String[0]),(d,n)->{
            if(n==names.size()-1) newList(t);
            else {store.add("list:"+names.get(n),t.key());Toast.makeText(this,"Трек добавлен",0).show();}
        }).show();
    }
    private void newList(Track t){
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Название");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Новый плейлист").setView(name)
            .setPositiveButton("Создать",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            String value=name.getText().toString().trim();
            if(value.isEmpty()||store.set("lists").contains(value)){name.setError("Введите уникальное название");return;}
            store.add("lists",value);if(t!=null)store.add("list:"+value,t.key());
            dialog.dismiss();listName=value;go("list");
        }));
        dialog.show();
    }
    private void listsPage(){
        body.addView(heading("Плейлисты",35));gap(body,12);body.addView(chip("Создать плейлист",()->newList(null),true));gap(body,12);
        for(String name:store.lists()){
            LinearLayout r=row();TextView title=heading(name+"  ·  "+store.set("list:"+name).size(),17);
            pad(title,12,12);r.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            title.setOnClickListener(v->{listName=name;go("list");});
            ImageButton delete=icon(R.drawable.ic_more,"Действия с плейлистом",()->new AlertDialog.Builder(this).setTitle("Удалить плейлист «"+name+"»?")
                .setPositiveButton("Удалить",(d,n)->{store.removeList(name);go("lists");}).setNegativeButton("Отмена",null).show());
            delete.setContentDescription("Удалить плейлист");r.addView(delete);body.addView(r);
        }
    }
    private void statsPage(){
        body.addView(heading("Прослушивания",35));gap(body,12);
        JSONObject all=store.stats();List<JSONObject> entries=new ArrayList<>();
        long millis=0,plays=0;
        for(Iterator<String> keys=all.keys();keys.hasNext();){
            JSONObject item=all.optJSONObject(keys.next());if(item==null)continue;
            entries.add(item);millis+=item.optLong("ms");plays+=item.optInt("plays");
        }
        TextView total=heading((millis/3600000)+" ч "+(millis/60000%60)+" мин  ·  "+plays+" треков",24);
        pad(total,16,22);total.setBackground(shape(PANEL,24));body.addView(total);gap(body,12);
        body.addView(label("Прослушивание засчитывается после 10 секунд. Данные хранятся на телефоне.",14,MUTED));gap(body,16);
        body.addView(heading("Чаще всего",20));
        entries.sort(Comparator.comparingLong((JSONObject v)->v.optLong("ms")).reversed());
        for(JSONObject item:entries){
            TextView t=label(item.optString("title")+" · "+item.optString("artist")+"\n"+
                clock(item.optLong("ms"))+"  ·  "+item.optInt("plays")+" прослуш.",15,TEXT);
            pad(t,6,9);body.addView(t);
        }
    }
    private void appsPage(){
        body.addView(heading("Настройки",37));gap(body,12);
        String mode=store.prefs.getString("theme","dark");
        String[] themeNames={"Системная","Светлая","Тёмная"};
        String[] themeKeys={"system","light","dark"};
        int selected=java.util.Arrays.asList(themeKeys).indexOf(mode);
        body.addView(chip("Тема · "+themeNames[Math.max(0,selected)],()->new AlertDialog.Builder(this)
            .setTitle("Тема оформления").setSingleChoiceItems(themeNames,selected,(dialog,index)->{
                store.prefs.edit().putString("theme",themeKeys[index]).apply();dialog.dismiss();recreate();
            }).show()));
        gap(body,8);
        body.addView(chip("Режим текста · "+(store.karaokeEnabled()?"Караоке":"Обычный"),this::chooseLyricsMode));
        gap(body,8);
        body.addView(chip("Источники текстов · "+Integer.bitCount(store.lyricSources())+" из "+Store.LYRIC_SOURCES.length,this::chooseLyricSources));
        gap(body,8);
        body.addView(chip("Подсветка вперёд · "+store.previewLetters(),this::previewSettings));
        gap(body,8);
        body.addView(chip("Опережение караоке · "+store.karaokeLead(),this::karaokeLeadSettings));
        gap(body,8);
        body.addView(chip("Размер субтитров · "+store.subtitleSize()+" sp",this::subtitleSettings));
        gap(body,8);
        body.addView(chip("Фон плеера",()->backdropSettings("player")));
        gap(body,8);
        body.addView(chip("Фон поля текста",()->backdropSettings("lyrics")));
        gap(body,8);
        addAudioPauseSetting("Пауза при отключении наушников или колонки",Store.PAUSE_ON_DISCONNECT,true);
        addAudioPauseSetting("Пауза при подключении наушников или колонки",Store.PAUSE_ON_CONNECT,false);
        String[] trackTap={"Открыть плеер с текстом","Остаться в текущем разделе"};
        int trackTapSelection=store.prefs.getBoolean("openPlayerOnTrack",true)?0:1;
        body.addView(chip("При нажатии на трек · "+trackTap[trackTapSelection],()->new AlertDialog.Builder(this)
            .setTitle("При нажатии на трек").setSingleChoiceItems(trackTap,trackTapSelection,(dialog,index)->{
                store.prefs.edit().putBoolean("openPlayerOnTrack",index==0).apply();
                dialog.dismiss();go("apps");
            }).show()));
        gap(body,8);
        body.addView(chip(LibraryScan.fullAccess(this)?"Пересканировать хранилище":"Разрешить поиск во всех папках",this::storageSettings));
        gap(body,22);
        body.addView(heading("Другие приложения",25));gap(body,10);
        body.addView(label("Craftify показывает активные медиасессии Android. Доступ к уведомлениям нужен для подключения.",15,MUTED));
        gap(body,10);
        body.addView(chip("Открыть настройки доступа",()->startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),true));
        gap(body,10);body.addView(chip("Обновить список",()->go("apps")));
        List<MediaController> controllers=External.sessions(this);
        if(controllers.isEmpty()){
            gap(body,14);body.addView(label("Запустите музыку в другом приложении, затем обновите список.",15,MUTED));
        }
        gap(body,8);body.addView(chip("Мой плеер",()->{remote=null;go("now");}));
        for(MediaController c:controllers){
            gap(body,8);MediaMetadata m=c.getMetadata();
            String title=m==null||m.getDescription().getTitle()==null?"Активная сессия":m.getDescription().getTitle().toString();
            body.addView(chip(appName(c)+" · "+title,()->{remote=c.getSessionToken();go("now");}));
        }
        gap(body,28);
        TextView about=label("Разработчик @InstallBat\nВерсия "+appVersion(),13,MUTED);
        about.setGravity(Gravity.CENTER);pad(about,0,8);body.addView(about);
    }
    private void addAudioPauseSetting(String title,String key,boolean defaultValue){
        Switch setting=new Switch(this);
        setting.setText(title);
        setting.setTextColor(TEXT);
        setting.setMinHeight(dp(52));
        setting.setBackground(shape(PANEL,24));
        pad(setting,14,8);
        setting.setChecked(store.prefs.getBoolean(key,defaultValue));
        setting.setOnCheckedChangeListener((button,checked)->store.prefs.edit().putBoolean(key,checked).apply());
        body.addView(setting,new LinearLayout.LayoutParams(-1,-2));
        gap(body,8);
    }
    private String appVersion(){
        try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}
        catch(android.content.pm.PackageManager.NameNotFoundException e){return "—";}
    }
    private void karaokeLeadSettings(){
        NumberPicker count=new NumberPicker(this);
        count.setMinValue(0);count.setMaxValue(30);count.setValue(store.karaokeLead());
        new AlertDialog.Builder(this).setTitle("Опережение караоке в символах")
            .setView(count).setPositiveButton("Сохранить",(d,n)->{
                count.clearFocus();
                store.prefs.edit().putInt("karaokeLead",count.getValue()).apply();
                go("apps");
            }).setNegativeButton("Отмена",null).show();
    }
    private void previewSettings(){
        NumberPicker count=new NumberPicker(this);
        count.setMinValue(0);count.setMaxValue(30);count.setValue(store.previewLetters());
        new AlertDialog.Builder(this).setTitle("Сколько букв подсвечивать вперёд")
            .setView(count).setPositiveButton("Сохранить",(d,n)->{
                count.clearFocus();store.prefs.edit().putInt("previewLetters",count.getValue()).apply();go("apps");
            }).setNegativeButton("Отмена",null).show();
    }
    private void subtitleSettings(){
        NumberPicker size=new NumberPicker(this);
        size.setMinValue(14);size.setMaxValue(48);size.setValue(store.subtitleSize());
        new AlertDialog.Builder(this).setTitle("Размер субтитров")
            .setView(size).setPositiveButton("Сохранить",(d,n)->{
                size.clearFocus();store.prefs.edit().putInt("subtitleSize",size.getValue()).apply();go("apps");
            }).setNegativeButton("Отмена",null).show();
    }
    private void backdropSettings(String key){
        LinearLayout controls=column();pad(controls,18,8);
        boolean selected=store.prefs.contains(key+"Backdrop");
        TextView imageButton=chip(selected?"Заменить изображение":"Выбрать изображение",()->{});
        controls.addView(imageButton);
        gap(controls,8);
        SeekBar blur=backdropSlider(controls,"Размытие",30,store.prefs.getInt(key+"Blur",0));
        SeekBar dark=backdropSlider(controls,"Затемнение",100,store.prefs.getInt(key+"Dark",60));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(key.equals("player")?"Фон плеера":"Фон поля текста")
            .setView(controls).setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить",(d,n)->store.prefs.edit().putInt(key+"Blur",blur.getProgress())
                .putInt(key+"Dark",dark.getProgress()).apply())
            .setNeutralButton("Сбросить",(d,n)->store.prefs.edit().remove(key+"Backdrop")
                .remove(key+"Blur").remove(key+"Dark").apply()).create();
        imageButton.setOnClickListener(v->{
            dialog.dismiss();
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*"),key.equals("player")?31:32);
        });
        dialog.show();
    }
    private SeekBar backdropSlider(LinearLayout controls,String name,int max,int current){
        TextView title=label(name+" · "+current,15,TEXT);controls.addView(title);
        SeekBar bar=new SeekBar(this);bar.setMax(max);bar.setProgress(current);bar.setContentDescription(name);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int value,boolean user){title.setText(name+" · "+value);}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        controls.addView(bar);
        return bar;
    }
    private void applyBackdrop(View view,String key){
        String uri=store.prefs.getString(key+"Backdrop",null);
        if(uri==null)return;
        int blur=store.prefs.getInt(key+"Blur",0),dark=store.prefs.getInt(key+"Dark",60);
        network.execute(()->{
            try{
                Backdrop image=Backdrop.load(this,uri,blur,dark);
                ui.post(()->{if(!isDestroyed()&&view.isAttachedToWindow())view.setBackground(image);});
            }catch(java.io.IOException | RuntimeException error){
                ui.post(()->{if(!isDestroyed()&&view.isAttachedToWindow())
                    Toast.makeText(this,"Фоновое изображение недоступно",Toast.LENGTH_SHORT).show();});
            }
        });
    }
    private void chooseLyricsMode(){
        new AlertDialog.Builder(this).setTitle("Режим текста")
            .setSingleChoiceItems(new String[]{"Обычный","Караоке"},store.karaokeEnabled()?1:0,(dialog,index)->{
                store.setKaraokeEnabled(index==1);
                dialog.dismiss();
                go("apps");
            })
            .setNegativeButton("Отмена",null).show();
    }
    private void chooseLyricSources(){
        boolean[] enabled=new boolean[Store.LYRIC_SOURCES.length];
        for(int i=0;i<enabled.length;i++)enabled[i]=(store.lyricSources()&(1<<i))!=0;
        new AlertDialog.Builder(this).setTitle("Источники текстов")
            .setMultiChoiceItems(Store.LYRIC_SOURCES,enabled,(dialog,index,checked)->enabled[index]=checked)
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить",(dialog,which)->{
                int mask=0;
                for(int i=0;i<enabled.length;i++)if(enabled[i])mask|=1<<i;
                store.prefs.edit().putInt("lyricSources",mask).apply();go("apps");
            }).show();
    }
    private String appName(MediaController c){
        try{return getPackageManager().getApplicationInfo(c.getPackageName(),0).loadLabel(getPackageManager()).toString();}
        catch(Exception e){return c.getPackageName();}
    }
    private MediaController controller(){
        if(remote==null)return null;
        for(MediaController c:External.sessions(this)) if(c.getSessionToken().equals(remote))return c;
        return null;
    }
    private void start(List<Track> items,int index,boolean openPlayer){
        MediaController external=controller();
        if(external!=null) external.getTransportControls().pause();
        remote=null;Player.open(this,items,index);if(openPlayer)go("now");
    }
    private void toggle(){
        MediaController c=controller();
        if(c!=null){
            if(isPlaying(c.getPlaybackState()))c.getTransportControls().pause();
            else {if(Player.current!=null)Player.current.pause();c.getTransportControls().play();}
        } else if(Player.current!=null){
            if(Player.current.playing())Player.current.pause();else Player.current.play();
        } else if(!page.equals("now"))go("now");
        update();
    }
    private void skip(int delta){
        MediaController c=controller();
        if(c!=null){if(delta<0)c.getTransportControls().skipToPrevious();else c.getTransportControls().skipToNext();}
        else if(Player.current!=null){if(delta<0)Player.current.previous();else Player.current.step(1);}
    }
    private boolean canSeek(long length) {
        if(length<=0)return false;
        MediaController c=controller();
        if(remote!=null){
            PlaybackState p=c==null?null:c.getPlaybackState();
            return p!=null&&(p.getActions()&PlaybackState.ACTION_SEEK_TO)!=0;
        }
        return Player.current!=null && Player.current.canSeek();
    }
    private void seekTo(long ms) {
        if(!canSeek(trackLength))return;
        ms=Math.max(0,Math.min(trackLength,ms));
        if(miniLyrics!=null)miniLyrics.resetProgress();
        MediaController c=controller();
        if(c!=null)c.getTransportControls().seekTo(ms);
        else if(Player.current!=null)Player.current.seek(ms);
    }
    private void nowPage(){
        LinearLayout header=row();
        ImageButton back=icon(R.drawable.ic_down,"Свернуть плеер",this::closePlayer);header.addView(back);
        largeArt=artwork(48);
        LinearLayout.LayoutParams coverParams=new LinearLayout.LayoutParams(dp(48),dp(48));coverParams.leftMargin=dp(8);
        header.addView(largeArt,coverParams);
        LinearLayout details=column();pad(details,10,0);
        expandedTitle=heading("",18);expandedTitle.setSingleLine(true);expandedTitle.setEllipsize(TextUtils.TruncateAt.END);details.addView(expandedTitle);
        expandedArtist=label("",12,MUTED);expandedArtist.setSingleLine(true);expandedArtist.setEllipsize(TextUtils.TruncateAt.END);details.addView(expandedArtist);
        header.addView(details,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton more=icon(R.drawable.ic_more,"Очередь и действия с треком",()->{
            Player p=Player.current;
            if(remote==null&&p!=null&&p.track()!=null)new AlertDialog.Builder(this).setItems(new String[]{"Очередь","Действия с треком"},
                (d,n)->{if(n==0)queue();else trackMenu(p.track());}).show();
        });
        more.setContentDescription("Очередь и действия с треком");header.addView(more);
        body.addView(header);gap(body,8);

        karaoke=new KaraokeView(this,this::seekTo,this::manualLyricsDialog);
        applyBackdrop(karaoke,"lyrics");
        body.addView(karaoke,new LinearLayout.LayoutParams(-1,0,1));
        seek=new SeekBar(this);
        seek.setMax(10000);seek.setContentDescription("Позиция воспроизведения");
        seek.setProgressTintList(ColorStateList.valueOf(ACCENT));seek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        body.addView(seek,new LinearLayout.LayoutParams(-1,dp(38)));
        time=label("0:00  /  0:00",13,MUTED);time.setGravity(Gravity.CENTER);body.addView(time);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int value,boolean user){
                if(user)time.setText(clock(trackLength*value/b.getMax())+"  /  "+clock(trackLength));
            }
            public void onStartTrackingTouch(SeekBar b){drag=true;}
            public void onStopTrackingTouch(SeekBar b){
                seekTo(trackLength*b.getProgress()/b.getMax());drag=false;
            }
        });
        gap(body,8);LinearLayout controls=row();
        ImageButton prev=icon(R.drawable.ic_previous,"Предыдущий трек",()->skip(-1));chipIn(controls,prev);
        expandedPlay=icon(R.drawable.ic_play,"Воспроизвести",this::toggle);expandedPlay.setBackground(shape(ACCENT,23));expandedPlay.setImageTintList(ColorStateList.valueOf(light?Color.WHITE:BG));chipIn(controls,expandedPlay);
        ImageButton next=icon(R.drawable.ic_next,"Следующий трек",()->skip(1));chipIn(controls,next);
        if(remote==null){
            shuffleButton=icon(R.drawable.ic_shuffle,"Перемешивание",()->{if(Player.current!=null){Player.current.toggleShuffle();updateModes();}});
            controls.addView(shuffleButton,0,new LinearLayout.LayoutParams(dp(46),dp(46)));
            repeatButton=icon(R.drawable.ic_repeat,"Повтор",()->{if(Player.current!=null){Player.current.cycleRepeat();updateModes();}});
            controls.addView(repeatButton,dp(46),dp(46));
        }
        body.addView(controls);gap(body,8);
        LinearLayout volume=row();
        ImageView speaker=new ImageView(this);speaker.setImageResource(R.drawable.ic_volume);
        speaker.setImageTintList(ColorStateList.valueOf(MUTED));volume.addView(speaker,dp(28),dp(32));
        volumeBar=new SeekBar(this);volumeBar.setContentDescription("Громкость устройства");
        volumeBar.setProgressTintList(ColorStateList.valueOf(ACCENT));volumeBar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        int stream=android.media.AudioManager.STREAM_MUSIC;
        volumeBar.setMax(audio.getStreamMaxVolume(stream));
        volumeBar.setProgress(audio.getStreamVolume(stream));
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int index,boolean user){if(user)audio.setStreamVolume(stream,index,0);}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        volume.addView(volumeBar,new LinearLayout.LayoutParams(0,dp(38),1));
        body.addView(volume);
        updateModes();
    }
    private void updateModes(){
        Player p=Player.current;
        if(shuffleButton==null||repeatButton==null)return;
        int repeat=p==null?0:p.repeat();
        boolean shuffle=p!=null&&p.shuffled();
        shuffleButton.setEnabled(p!=null);repeatButton.setEnabled(p!=null);
        shuffleButton.setImageTintList(ColorStateList.valueOf(shuffle?ACCENT:MUTED));
        repeatButton.setImageTintList(ColorStateList.valueOf(repeat!=0?ACCENT:MUTED));
        repeatButton.setImageResource(repeat==1?R.drawable.ic_repeat_one:R.drawable.ic_repeat);
        repeatButton.setContentDescription(repeat==1?"Повтор трека":repeat==2?"Повтор очереди":"Повтор выключен");
        shuffleButton.setContentDescription(shuffle?"Случайный порядок":"По порядку");
    }
    private void queue(){
        Player p=Player.current;
        if(remote!=null || p==null)return;
        List<Track> q=p.queue();String[] titles=new String[q.size()];
        for(int i=0;i<titles.length;i++)titles[i]=q.get(i).title;
        new AlertDialog.Builder(this).setTitle("Очередь").setItems(titles,(d,n)->p.playQueueItem(n)).show();
    }
    private static long duration(MediaController c){
        MediaMetadata m=c.getMetadata();return m==null?0:m.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }
    private static boolean isPlaying(PlaybackState p){return p!=null && p.getState()==PlaybackState.STATE_PLAYING;}
    private static String clock(long ms){long s=Math.max(0,ms)/1000;return String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
    private Bitmap shownCover;
    private ImageView shownArt;
    private String shownSource="";
    private Player modePlayer;
    private void setPlayIcon(ImageButton button,boolean playing){
        if(Boolean.valueOf(playing).equals(button.getTag()))return;
        button.setTag(playing);
        button.setImageResource(playing?R.drawable.ic_pause:R.drawable.ic_play);
        button.setContentDescription(playing?"Пауза":"Воспроизвести");
    }
    private void update(){
        updateOverlayButton();
        Player local=Player.current;
        Track t=local==null?null:local.track();
        MediaController c=controller();
        String title="",artist="",source="";
        long pos=0,len=0;
        Bitmap cover=null;
        boolean playing=false;
        if(remote!=null) {
            if(c==null) source="Сессия завершена";
            else {
                source=appName(c);
                MediaMetadata m=c.getMetadata();
                PlaybackState p=c.getPlaybackState();
                if(m!=null){
                    CharSequence name=m.getDescription().getTitle();
                    title=name==null?"":name.toString();artist=Track.clean(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
                    cover=m.getDescription().getIconBitmap();len=duration(c);
                }
                playing=isPlaying(p);
                if(p!=null)pos=Math.max(0,p.getPosition()+(playing && p.getLastPositionUpdateTime()>0?(long)((SystemClock.elapsedRealtime()-p.getLastPositionUpdateTime())*p.getPlaybackSpeed()):0));
            }
        } else if(t!=null) {
            title=t.title;artist=t.artist;source="На устройстве";cover=local.cover;
            pos=local.time();len=local.length();playing=local.playing();
        }
        if(len>0)pos=Math.min(pos,len);
        now.setText(title.isEmpty()?"Craftify":title);
        by.setText(artist.isEmpty()?(source.isEmpty()?"Выберите трек":source):artist+" · "+source);
        setPlayIcon(play,playing);
        if(expandedTitle!=null) {
            expandedTitle.setText(title.isEmpty()?"Выберите трек":title);
            expandedArtist.setText(by.getText());
            setPlayIcon(expandedPlay,playing);
            if(volumeBar!=null&&!volumeBar.isPressed())volumeBar.setProgress(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC));
            if(local!=modePlayer){modePlayer=local;updateModes();}
        }
        String coverSource=source+"\n"+title;
        if(shownCover!=cover || shownArt!=art || !shownSource.equals(coverSource)){
            shownCover=cover;shownArt=art;shownSource=coverSource;
            coverTint=IslandVisuals.accent(cover);
            island.setBackground(IslandVisuals.glass(coverTint,dp(40),false));
            if(karaoke!=null&&!(karaoke.getBackground() instanceof Backdrop))karaoke.setBackground(IslandVisuals.glass(coverTint,dp(28),true));
            if(seek!=null){seek.setProgressTintList(ColorStateList.valueOf(coverTint));seek.setThumbTintList(ColorStateList.valueOf(coverTint));}
            if(cover==null){art.setImageResource(R.drawable.island);if(largeArt!=null)largeArt.setImageResource(R.drawable.island);}
            else {art.setImageBitmap(cover);if(largeArt!=null)largeArt.setImageBitmap(cover);}
        }
        String key=title.isEmpty()?"":(remote==null&&t!=null?t.key():source)+"\n"+artist+"\n"+title;
        manualTarget=key.isEmpty()?null:remote==null&&t!=null?t.key():key;
        loadLyrics(key,title,artist,len);
        Lyrics.Frame frame=lyric==null?null:lyric.frame(pos,len,store.karaokeEnabled()?store.karaokeLead():0);
        miniLyrics.setVisibility(frame==null?View.INVISIBLE:View.VISIBLE);
        now.setVisibility(frame==null?View.VISIBLE:View.INVISIBLE);
        by.setVisibility(frame==null?View.VISIBLE:View.INVISIBLE);
        PlaybackState lyricState=c==null?null:c.getPlaybackState();
        miniLyrics.display(frame,"",Color.WHITE,playing?(lyricState==null?1:lyricState.getPlaybackSpeed()):0);
        musicPulse.display(playing,coverTint);
        trackLength=len;
        if(seek!=null){
            boolean available=canSeek(len);
            seek.setEnabled(available);
            long display=drag&&len>0?len*seek.getProgress()/seek.getMax():pos;
            if(!drag)seek.setProgress(len>0?(int)(seek.getMax()*pos/len):0);
            time.setText(clock(display)+"  /  "+clock(len));
            PlaybackState state=c==null?null:c.getPlaybackState();
            float speed=drag||!playing?0:state==null?1:state.getPlaybackSpeed();
            String status=key.isEmpty()?"Выберите трек":store.lyricSources()==0?"Источники текстов выключены":loading?"Ищем текст…":
                lyric==null?"Текст не найден":"Для этой песни нет синхронного текста";
            boolean canImport=manualTarget!=null&&!loading&&(lyric==null||lyric.text.isEmpty());
            if(canImport)status+="\nНажмите, чтобы добавить свой текст";
            karaoke.display(lyric,status,display,len,speed,coverTint,available,canImport);
        }
    }
    private void manualLyricsDialog(){
        String target=manualTarget;
        if(target==null)return;
        EditText input=new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint("Вставьте текст песни");input.setMinLines(4);input.setMaxLines(9);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Свой текст песни").setView(input)
            .setPositiveButton("Сохранить",null)
            .setNeutralButton("Открыть файл",(d,n)->{
                pendingManual=target;
                Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*");
                startActivityForResult(picker,33);
            }).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(saveManualLyrics(target,input.getText().toString()))dialog.dismiss();
            else input.setError("Введите текст песни длиной до 128 000 символов");
        }));
        dialog.show();
    }
    private void readManualLyrics(String target,Uri uri){
        io.execute(()->{
            try(java.io.Reader reader=new java.io.InputStreamReader(getContentResolver().openInputStream(uri),java.nio.charset.StandardCharsets.UTF_8)){
                StringBuilder value=new StringBuilder();
                char[] buffer=new char[4096];
                int size;
                while((size=reader.read(buffer))!=-1){
                    value.append(buffer,0,size);
                    if(value.length()>128000)break;
                }
                ui.post(()->{
                    if(!isDestroyed()&&!saveManualLyrics(target,value.toString()))
                        Toast.makeText(this,"Файл пуст или слишком большой",Toast.LENGTH_SHORT).show();
                });
            }catch(Exception error){
                ui.post(()->{if(!isDestroyed())Toast.makeText(this,"Не удалось прочитать текст",Toast.LENGTH_SHORT).show();});
            }
        });
    }
    private boolean saveManualLyrics(String target,String text){
        String value=text.replaceFirst("^\uFEFF","").trim();
        if(value.isEmpty()||value.length()>128000)return false;
        store.prefs.edit().putString("manualLyrics:"+target,value).apply();
        lyricKey=null;
        update();
        return true;
    }
    private void loadLyrics(String key,String title,String artist,long ms){
        String manual=manualTarget==null?null:store.prefs.getString("manualLyrics:"+manualTarget,null);
        if(manual!=null){
            String manualKey="manual|"+manualTarget;
            if(!manualKey.equals(lyricKey)){lyricKey=manualKey;lyric=Lyrics.manual(manual);loading=false;}
            return;
        }
        int sources=store.lyricSources();
        String requestKey=key.isEmpty()?"":sources+"|"+key;
        if(requestKey.equals(lyricKey))return;
        lyricKey=requestKey;lyric=lyricCache.get(requestKey);loading=false;
        if(sources==0||key.isEmpty()||lyricCache.containsKey(requestKey))return;
        loading=true;
        network.execute(()->{
            Lyrics found=null;boolean completed=false;
            try{found=Lyrics.lookup(title,artist,ms,sources);completed=true;}catch(Exception ignored){}
            Lyrics result=found;boolean cache=completed;
            ui.post(()->{
                if(isDestroyed())return;
                if(cache){if(lyricCache.size()>=128)lyricCache.clear();lyricCache.put(requestKey,result);}
                if(requestKey.equals(lyricKey)){lyric=result;loading=false;update();}
            });
        });
    }
}






















