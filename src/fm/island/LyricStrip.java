package fm.island;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

final class LyricStrip extends View {
    private static final int UNPLAYED=0xffa9aab3;
    private static final int NEIGHBOR=0xff858995;
    private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
    private final Store settings;
    private final boolean compact;

    private Lyrics.Frame frame;
    private String text="", outgoing="";
    private long changedAt,sampledAt;
    private float rate;
    private double progress, clockAtSample;
    private String measuredText;
    private float measuredSize,measuredWidth;
    private float[] advances=new float[0];
    private boolean motion,karaoke;
    private float scrollOffset,outgoingOffset;
    private boolean reset=true;
    private int whitePrefix;
    private int accent=Color.WHITE;
    private int shaderColor;
    private LinearGradient edgeShader;
    private final android.graphics.Matrix edgePosition=new android.graphics.Matrix();

    LyricStrip(Context context,boolean compact) {
        super(context);
        this.compact=compact;
        settings=new Store(context);
        textPaint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void resetProgress(){reset=true;}

    void display(Lyrics.Frame next,String fallback,int color,float speed) {
        String value=next==null?fallback:next.current;
        long now=SystemClock.uptimeMillis();
        boolean changed=!text.equals(value)||(frame==null)!=(next==null)||frame!=null&&next!=null&&frame.cue!=next.cue;
        if(changed){
            whitePrefix=0;
            outgoing=text;
            outgoingOffset=scrollOffset;
            changedAt=now;
            setContentDescription(value);
        }
        boolean seek=frame!=null&&next!=null&&
            Math.abs((next.fraction-frame.fraction)*next.span-(now-sampledAt)*rate)>650;
        double observed=next==null?0:next.fraction;
        if(changed||reset||seek||next==null){
            progress=observed;clockAtSample=observed;
            scrollOffset=0;
        }
        else if(speed>0&&rate>0&&next.span==frame.span){
            double predicted=clockAtSample+(now-sampledAt)*(double)rate/next.span;
            clockAtSample=predicted+Math.max(-.025,Math.min(.025,(observed-predicted)*.18));
        }else clockAtSample=observed;
        motion=settings.prefs.getBoolean("motion",true);
        karaoke=settings.karaokeEnabled();
        reset=false;
        frame=next;
        text=value;
        accent=color;
        sampledAt=now;
        rate=Math.max(0,speed);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now=SystemClock.uptimeMillis(), age=now-changedAt;
        float density=getResources().getDisplayMetrics().density;
        float size=(compact?15:17)*getResources().getDisplayMetrics().scaledDensity;
        float center=getHeight()/2f;

        float phase=motion?Math.min(1,age/260f):1;
        float shift=(1-phase)*(1-phase)*6*density;

        float left=getPaddingLeft(),right=getWidth()-getPaddingRight();
        if(right<=left)return;
        canvas.save();
        canvas.clipRect(left,0,right,getHeight());

        if(!compact&&frame!=null){
            float step=getHeight()/3.15f;
            draw(canvas,frame.previous,left,center-step,size*.91f,NEIGHBOR,1,-1);
            draw(canvas,frame.next,left,center+step,size*.91f,NEIGHBOR,1,-1);
        }
        if(compact&&phase<1&&!outgoing.isEmpty())
            draw(canvas,outgoing,left-outgoingOffset,center-phase*6*density,size,UNPLAYED,1-phase,-1);

        textPaint.setTextSize(size);
        measure(size);
        float width=measuredWidth;
        float overflow=Math.max(0,width-(right-left));
        float colored=-1, preview=-1;
        if(frame!=null){
            double position=clockAtSample+(now-sampledAt)*(double)rate/frame.span;
            progress=Math.max(progress,Math.min(1,position));
            colored=width+2;
            if(karaoke&&frame.cue!=null){
                long end=frame.cue.end(frame.start+frame.span);
                long wordPosition=frame.start+(long)(progress*frame.span);
                whitePrefix=Math.max(whitePrefix,frame.cue.sungPrefix(wordPosition,end));
                colored=advances[whitePrefix];
                preview=advances[frame.cue.previewEnd(whitePrefix,settings.previewLetters())];
            }
            // Cover the measured overflow in the cue's duration. Wider lines move faster.
            if(motion)scrollOffset=Math.min(overflow,Math.max(scrollOffset,(float)(overflow*progress)));
        }
        float x=left-(motion?scrollOffset:0);
        if(!compact&&overflow==0)x+=(right-left-width)/2;
        draw(canvas,text,x,center+shift,size,frame==null?accent:UNPLAYED,phase,colored);
        if(preview>colored){
            int band=canvas.save();
            canvas.clipRect(x+colored,0,x+preview,getHeight());
            draw(canvas,text,x,center+shift,size,0xffd4d4d9,phase,-1);
            canvas.restoreToCount(band);
        }
        canvas.restore();

        if(phase<1 || frame!=null&&rate>0&&progress<1)postInvalidateOnAnimation();
    }

    private void measure(float size){
        if(text.equals(measuredText)&&size==measuredSize)return;
        measuredText=text;measuredSize=size;
        float[] widths=new float[text.length()];
        textPaint.getTextWidths(text,widths);
        advances=new float[widths.length+1];
        for(int i=0;i<widths.length;i++)advances[i+1]=advances[i]+widths[i];
        measuredWidth=advances[widths.length];
    }

    private void draw(Canvas canvas,String value,float x,float center,float size,int color,float alpha,float colored){
        if(value.isEmpty()||alpha<=0)return;
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        if(colored>0){
            if(edgeShader==null||shaderColor!=accent){
                shaderColor=accent;
                edgeShader=new LinearGradient(-1,0,1,0,new int[]{accent,accent,UNPLAYED,UNPLAYED},
                    new float[]{0,.5f,.5f,1},Shader.TileMode.CLAMP);
            }
            edgePosition.setTranslate(x+colored,0);
            edgeShader.setLocalMatrix(edgePosition);
            textPaint.setShader(edgeShader);
        }
        textPaint.setAlpha(Math.round(alpha*255));
        canvas.drawText(value,x,center-(textPaint.ascent()+textPaint.descent())/2,textPaint);
        textPaint.setShader(null);
    }
}










