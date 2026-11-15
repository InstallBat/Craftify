package fm.island;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.text.Layout;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.LongConsumer;

final class KaraokeView extends FrameLayout {
    private final ScrollView scroll;
    private final Store settings;
    private android.animation.ValueAnimator autoScroll;
    private final LinearLayout content;
    private final TextView follow,empty,plain;
    private final LongConsumer seek;
    private final Path frameClip=new Path();
    private final List<WordRow> rows=new ArrayList<>();
    private Lyrics lyrics;
    private long position,sampled,duration;
    private float rate;
    private int active=-1,highlighted;
    private boolean following=true,seekable;
    private int tint=0xffc9b8ff;

    KaraokeView(Context c,LongConsumer seek,Runnable importLyrics) {
        super(c);
        this.seek=seek;settings=new Store(c);
        scroll=new ScrollView(c){
            @Override public boolean onInterceptTouchEvent(MotionEvent event){
                boolean intercepted=super.onInterceptTouchEvent(event);
                if(intercepted&&event.getActionMasked()==MotionEvent.ACTION_MOVE&&!rows.isEmpty()){
                    if(autoScroll!=null)autoScroll.cancel();
                    following=false;follow.setVisibility(VISIBLE);
                }
                return intercepted;
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        content=new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        plain=new TextView(c);
        plain.setTextColor(Color.WHITE);
        plain.setTextSize(settings.subtitleSize());
        plain.setPadding(dp(9),dp(12),dp(9),dp(12));
        plain.setVisibility(GONE);
        scroll.addView(content);
        addView(scroll,new LayoutParams(-1,-1));
        empty=new TextView(c);
        empty.setTextColor(0xffaaa7b6);empty.setTextSize(17);empty.setGravity(Gravity.CENTER);
        addView(empty,new LayoutParams(-1,-1));
        empty.setOnClickListener(v->importLyrics.run());
        follow=new TextView(c);
        follow.setText("К текущей строке");follow.setTextColor(Color.WHITE);
        follow.setGravity(Gravity.CENTER);follow.setTextSize(13);follow.setPadding(dp(15),0,dp(15),0);
        follow.setBackground(IslandVisuals.glass(tint,dp(24),false));
        LayoutParams button=new LayoutParams(-2,dp(44),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        button.bottomMargin=dp(6);addView(follow,button);follow.setVisibility(GONE);
        follow.setOnClickListener(v->{following=true;follow.setVisibility(GONE);center(true);});
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            int inset=rows.isEmpty()?0:(b-t)/3;
            if(scroll.getPaddingTop()!=inset)scroll.setPadding(0,inset,0,inset*2);
            if(following && b-t!=ob-ot) scroll.post(()->center(false));
        });
    }

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    void display(Lyrics value,String status,long ms,long length,float speed,int accent,boolean canSeek,boolean canImport) {
        if(lyrics!=value) {
            if(autoScroll!=null)autoScroll.cancel();
            lyrics=value;content.removeAllViews();rows.clear();active=-1;following=true;
            follow.setVisibility(GONE);scroll.scrollTo(0,0);
            boolean showPlain=value!=null&&value.cues().isEmpty()&&!value.text.isEmpty();
            plain.setText(showPlain?value.text:"");
            plain.setVisibility(showPlain?VISIBLE:GONE);
            content.addView(plain,new LinearLayout.LayoutParams(-1,-2));
            scroll.setPadding(0,0,0,0);
            if(value!=null)for(Lyrics.Cue cue:value.cues()){
                WordRow row=new WordRow(cue);rows.add(row);content.addView(row,new LinearLayout.LayoutParams(-1,-2));
            }
        }
        if(!status.contentEquals(empty.getText()))empty.setText(status);
        int inset=rows.isEmpty()?0:scroll.getHeight()/3;
        if(scroll.getPaddingTop()!=inset)scroll.setPadding(0,inset,0,inset*2);
        empty.setVisibility(rows.isEmpty()&&plain.getVisibility()==GONE?VISIBLE:GONE);
        empty.setClickable(canImport);
        empty.setFocusable(canImport);
        seekable=canSeek;
        if(tint!=accent){tint=accent;follow.setBackground(IslandVisuals.glass(tint,dp(24),false));}
        duration=length;
        position=ms;sampled=SystemClock.uptimeMillis();rate=speed;
        updateFrame();
    }
    private long playbackTime(){
        long time=position+(long)((SystemClock.uptimeMillis()-sampled)*rate);
        return duration>0?Math.min(duration,time):time;
    }
    private long end(int i){
        long start=rows.get(i).cue.start;
        long next=i+1<rows.size()?rows.get(i+1).cue.start:duration>start?duration:start+4000;
        return rows.get(i).cue.end(next);
    }
    private void center(boolean animated){
        if(active<0||active>=rows.size())return;
        int target=Math.max(0,rows.get(active).getTop()+scroll.getPaddingTop()-scroll.getHeight()/3);
        if(autoScroll!=null)autoScroll.cancel();
        if(animated&&scroll.getScrollY()!=target){
            autoScroll=android.animation.ValueAnimator.ofInt(scroll.getScrollY(),target);
            autoScroll.setInterpolator(new android.view.animation.PathInterpolator(.22f,1f,.36f,1f));
            autoScroll.setDuration(440);
            autoScroll.addUpdateListener(animation->scroll.scrollTo(0,(int)animation.getAnimatedValue()));
            autoScroll.start();
        }else scroll.scrollTo(0,target);
    }
    private void updateFrame(){
        if(rows.isEmpty())return;
        long ms=playbackTime();
        Lyrics.Highlight highlight=lyrics.highlight(ms,duration,settings.karaokeEnabled()?settings.karaokeLead():0);
        int next=highlight.line;
        highlighted=highlight.prefix;
        if(next!=active){
            int previous=active;active=next;
            if(previous>=0)rows.get(previous).highlight(false);
            if(active>=0)rows.get(active).highlight(true);
            if(following)scroll.post(()->{if(following)center(true);});
        }
        if(active>=0)rows.get(active).invalidate();
        if(rate!=0&&isShown())postInvalidateOnAnimation();
    }
    @Override protected void onSizeChanged(int w,int h,int oldWidth,int oldHeight){
        super.onSizeChanged(w,h,oldWidth,oldHeight);
        frameClip.reset();
        int radius=dp(28);
        frameClip.addRoundRect(0,0,w,h,radius,radius,Path.Direction.CW);
    }
    @Override public void draw(Canvas canvas){
        int save=canvas.save();
        canvas.clipPath(frameClip);
        super.draw(canvas);
        canvas.restoreToCount(save);
    }
    @Override protected void dispatchDraw(Canvas canvas){
        if(rate!=0)updateFrame();
        super.dispatchDraw(canvas);
    }

    private final class WordRow extends TextView {
        final Lyrics.Cue cue;
        final Path selection=new Path();
        private int touched;
        WordRow(Lyrics.Cue cue){
            super(KaraokeView.this.getContext());this.cue=cue;
            setText(cue.text);setTextColor(Color.WHITE);setAlpha(.36f);
            setTextSize(settings.subtitleSize());setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
            setPadding(dp(9),dp(12),dp(9),dp(12));
            setClickable(true);
        }
        void highlight(boolean current){animate().cancel();animate().alpha(current?1f:.36f).setDuration(360).start();invalidate();}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_UP)
                touched=getOffsetForPosition(event.getX(),event.getY());
            return super.onTouchEvent(event);
        }
        @Override public boolean performClick(){
            super.performClick();
            int index=rows.indexOf(this);
            if(!seekable||index<0)return true;
            following=true;follow.setVisibility(GONE);
            seek.accept(settings.karaokeEnabled()?cue.timeAt(touched,end(index)):cue.start);
            return true;
        }
        @Override protected void onDraw(Canvas canvas){
            Layout layout=getLayout();
            if(active<0 || rows.get(active)!=this || layout==null || !settings.karaokeEnabled()){super.onDraw(canvas);return;}
            android.text.TextPaint paint=layout.getPaint();
            int color=paint.getColor();
            int save=canvas.save();
            canvas.translate(getTotalPaddingLeft(),getTotalPaddingTop());
            paint.setColor(0xffb4aeba);
            layout.draw(canvas);

            int previewEnd=cue.previewEnd(highlighted,settings.previewLetters());
            if(previewEnd>highlighted){
                int preview=canvas.save();
                selection.reset();
                layout.getSelectionPath(highlighted,previewEnd,selection);
                canvas.clipPath(selection);
                paint.setColor(0xffdad7dd);
                layout.draw(canvas);
                canvas.restoreToCount(preview);
            }
            if(highlighted>0){
                selection.reset();
                layout.getSelectionPath(0,highlighted,selection);
                canvas.clipPath(selection);
                paint.setColor(Color.WHITE);
                layout.draw(canvas);
            }
            paint.setColor(color);
            canvas.restoreToCount(save);
        }
    }
}










