package fm.island;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.json.*;

final class Lyrics {
    private static final Pattern STAMP=Pattern.compile("\\[(\\d{1,4}):(\\d{2}(?:[.:]\\d{1,3})?)\\]");
    private final NavigableMap<Long,String> lines=new TreeMap<>();
    final String text;
    private static final Pattern WORD_TIME=Pattern.compile("<(\\d{1,4}):(\\d{2}(?:[.:]\\d{1,3})?)>");
    private static final Pattern WORD=Pattern.compile("\\S+");
    private final Map<Long,Cue> parsed=new TreeMap<>();
    private final List<Cue> timeline;

    static final class Cue {
        final long start, explicitEnd;
        final String text;
        final List<Integer> offsets=new ArrayList<>();
        final List<Long> times=new ArrayList<>();
        final List<Integer> wordEnds=new ArrayList<>();
        final List<Integer> characterStarts=new ArrayList<>();
        final int characters;
        private final List<int[]> letterEnds=new ArrayList<>();
        private final int[] allLetterEnds;
        Cue(long start,String text,List<Integer> tags,List<Long> stamps){
            this.start=start;this.text=text;
            explicitEnd=!tags.isEmpty()&&tags.get(tags.size()-1)==text.length()?stamps.get(stamps.size()-1):0;
            Matcher words=WORD.matcher(text);
            java.text.BreakIterator letters=java.text.BreakIterator.getCharacterInstance(Locale.ROOT);
            letters.setText(text);
            int marker=-1,count=0;
            while(words.find()){
                while(marker+1<tags.size() && tags.get(marker+1)<=words.start())marker++;
                offsets.add(words.start());wordEnds.add(words.end());
                List<Integer> ends=new ArrayList<>();
                for(int at=letters.following(words.start());at!=java.text.BreakIterator.DONE&&at<=words.end();at=letters.next())
                    ends.add(at);
                if(ends.isEmpty()||ends.get(ends.size()-1)!=words.end())ends.add(words.end());
                int[] boundaries=new int[ends.size()];
                for(int i=0;i<boundaries.length;i++)boundaries[i]=ends.get(i);
                letterEnds.add(boundaries);
                characterStarts.add(count);count+=boundaries.length;
                times.add(marker<0?-1:Math.max(start,stamps.get(marker)));
            }
            characters=count;
            allLetterEnds=new int[count];
            int next=0;
            for(int[] word:letterEnds){
                System.arraycopy(word,0,allLetterEnds,next,word.length);
                next+=word.length;
            }
        }
        boolean wordTimed(){return times.stream().anyMatch(time->time>=0);}
        long end(long fallback){return explicitEnd>start?Math.min(explicitEnd,fallback):fallback;}
        private long boundary(int word,long end){
            if(word>=offsets.size())return end;
            long stamp=times.get(word);
            if(stamp>=0)return Math.min(end,stamp);
            return start+(end-start)*characterStarts.get(word)/Math.max(1,characters);
        }
        float wordProgress(int word,long position,long end){
            long from=boundary(word,end),to=boundary(word+1,end);
            if(position<from)return 0;
            if(position>=to)return 1;
            return (position-from)/(float)(to-from);
        }
        int previewEnd(int whitePrefix,int limit){
            if(limit<=0)return whitePrefix;
            int index=Arrays.binarySearch(allLetterEnds,whitePrefix);
            int following=index>=0?index+1:-index-1;
            if(following>=allLetterEnds.length)return whitePrefix;
            int nextLetter=allLetterEnds[following];
            int word=Collections.binarySearch(wordEnds,nextLetter);
            if(word<0)word=-word-1;
            if(word<letterEnds.size()&&letterEnds.get(word).length<=4)return wordEnds.get(word);
            int last=Math.min(allLetterEnds.length,following+limit)-1;
            return last<following?whitePrefix:allLetterEnds[last];
        }
        int sungPrefix(long position,long end){
            int prefix=0;
            for(int word=0;word<letterEnds.size();word++){
                int[] letters=letterEnds.get(word);
                int sung=(int)(wordProgress(word,position,end)*letters.length);
                if(sung>0)prefix=letters[sung-1];
                if(sung<letters.length)break;
            }
            return prefix;
        }
        long timeAt(int character,long end){
            int word=Collections.binarySearch(offsets,character);
            if(word<0)word=Math.max(0,-word-2);
            return boundary(word,end);
        }
        float offsetAt(long position,long end){
            if(offsets.isEmpty()||position<start)return 0;
            if(position>=end)return text.length();
            int word=0;
            while(word+1<offsets.size()&&position>=boundary(word+1,end))word++;
            long from=boundary(word,end),to=boundary(word+1,end);
            int left=offsets.get(word),right=word+1<offsets.size()?offsets.get(word+1):text.length();
            return left+(right-left)*Math.max(0,Math.min(1,(position-from)/(float)Math.max(1,to-from)));
        }
    }
    private void addCue(long start,String raw){
        Matcher tags=WORD_TIME.matcher(raw);
        StringBuilder content=new StringBuilder();
        List<Integer> offsets=new ArrayList<>();
        List<Long> timestamps=new ArrayList<>();
        int copied=0;
        while(tags.find()){
            content.append(raw,copied,tags.start());copied=tags.end();
            offsets.add(content.length());
            timestamps.add(Long.parseLong(tags.group(1))*60000+
                Math.round(Double.parseDouble(tags.group(2).replace(':','.'))*1000));
        }
        content.append(raw,copied,raw.length());
        String value=content.toString();
        lines.put(start,value);
        parsed.put(start,new Cue(start,value,offsets,timestamps));
    }
    List<Cue> cues(){return timeline;}
    int cueIndex(long ms){
        int low=0,high=timeline.size();
        while(low<high){
            int middle=(low+high)/2;
            if(timeline.get(middle).start<=ms)low=middle+1;else high=middle;
        }
        return low-1;
    }
    static Lyrics manual(String value){return new Lyrics(value,true);}
    private Lyrics(String value,boolean manual){
        timeline=Collections.emptyList();
        text=value;
    }
    Lyrics(String value) {
        StringBuilder plain=new StringBuilder();
        for(String row:value.split("\\r?\\n")) {
            Matcher stamps=STAMP.matcher(row.trim());
            List<Long> times=new ArrayList<>();
            int end=0;
            while(stamps.find() && stamps.start()==end) {
                times.add(Long.parseLong(stamps.group(1))*60000+
                    Math.round(Double.parseDouble(stamps.group(2).replace(':','.'))*1000));
                end=stamps.end();
            }
            if(!times.isEmpty()) {
                String line=row.trim().substring(end).trim();
                for(long time:times) addCue(time,line);
            } else if(!row.trim().matches("\\[[a-zA-Z]+:.*\\]")) plain.append(row).append('\n');
        }
        timeline=new ArrayList<>(parsed.values());
        text=lines.isEmpty()?plain.toString().trim():String.join("\n",lines.values()).trim();
    }
    String line(long time) {
        Map.Entry<Long,String> entry=lines.floorEntry(time);
        return entry==null ? "" : entry.getValue();
    }
    static final class Frame {
        final String previous,current,next;
        final float fraction;
        final long start, span;
        final Cue cue;
        final int highlightedPrefix;
        Frame(String previous,String current,String next,float fraction,long start,long span,Cue cue) {
            this(previous,current,next,fraction,start,span,cue,-1);
        }
        Frame(String previous,String current,String next,float fraction,long start,long span,Cue cue,int highlightedPrefix){
            this.highlightedPrefix=highlightedPrefix;
            this.previous=previous;this.current=current;this.next=next;this.fraction=fraction;this.start=start;this.span=span;this.cue=cue;
        }
    }
    static final class Highlight {
        final int line,prefix;
        Highlight(int line,int prefix){this.line=line;this.prefix=prefix;}
    }
    Highlight highlight(long position,long duration,int lead){
        int line=cueIndex(position);
        if(line<0)return new Highlight(-1,0);
        Cue cue=timeline.get(line);
        long next=line+1<timeline.size()?timeline.get(line+1).start:duration>cue.start?duration:cue.start+4000;
        int prefix=cue.sungPrefix(position,cue.end(next));
        if(lead<=0)return new Highlight(line,prefix);
        int index=Arrays.binarySearch(cue.allLetterEnds,prefix);
        int count=(index>=0?index+1:-index-1)+lead;
        while(count>cue.characters&&line+1<timeline.size()){
            count-=cue.characters;
            cue=timeline.get(++line);
        }
        int highlighted=cue.characters==0?0:cue.allLetterEnds[Math.min(count,cue.characters)-1];
        return new Highlight(line,highlighted);
    }
    Frame frame(long position,long duration,int lead){
        if(lead<=0)return frame(position,duration);
        Highlight highlight=highlight(position,duration,lead);
        if(highlight.line<0)return null;
        Cue cue=timeline.get(highlight.line);
        Frame frame=frame(cue.start,duration);
        if(frame==null)return null;
        return new Frame(frame.previous,frame.current,frame.next,
            highlight.prefix/(float)cue.text.length(),frame.start,frame.span,cue,highlight.prefix);
    }
    Frame frame(long position,long duration) {
        Map.Entry<Long,String> current=lines.floorEntry(position);
        if(current==null || current.getValue().isEmpty())return null;
        Map.Entry<Long,String> previous=lines.lowerEntry(current.getKey());
        Map.Entry<Long,String> next=lines.higherEntry(current.getKey());
        long end=next==null?(duration>current.getKey()?duration:current.getKey()+4000):next.getKey();
        float fraction=Math.min(1,Math.max(0,(position-current.getKey())/(float)Math.max(1,end-current.getKey())));
        return new Frame(previous==null?"":previous.getValue(),current.getValue(),next==null?"":next.getValue(),fraction,current.getKey(),Math.max(1,end-current.getKey()),parsed.get(current.getKey()));
    }
    static Lyrics lookup(String title,String artist,long duration,int sources) throws Exception {
        String clean=title.replaceAll("(?i)\\s*[\\[(][^\\])]*(official|video|audio|lyrics?|remaster|клип)[^\\])]*[\\])]","").trim();
        artist=artist.replaceFirst("(?i)\\s*-\\s*Topic$","").trim();
        if(artist.isEmpty() && clean.contains(" - ")) {
            String[] parts=clean.split(" - ",2);
            artist=parts[0].trim();
            clean=parts[1].trim();
        }
        String query="track_name="+encode(clean)+(artist.isEmpty()?"":"&artist_name="+encode(artist));
        Lyrics result=null;
        if((sources&1)!=0)try {
            result=best(fetch(query),clean,artist,duration);
            if(result==null) result=best(fetch("q="+encode((artist+" "+clean).trim())),clean,artist,duration);
        } catch(IOException | JSONException ignored) {}
        if(result!=null && result.hasTimeline(duration)){
            if(result.hasWordTiming() || (sources&4)==0)return result;
            Lyrics karaoke=Catalogues.lookup(clean,artist,duration,4);
            return karaoke!=null&&karaoke.hasWordTiming()?karaoke:result;
        }
        Lyrics alternate=Catalogues.lookup(clean,artist,duration,sources);
        return alternate==null?result:alternate;
    }
    boolean hasWordTiming(){return timeline.stream().anyMatch(Cue::wordTimed);}
    boolean hasTimeline(long duration) {
        return !text.isEmpty() && !lines.isEmpty() && (duration<=0 || lines.lastKey()<=duration+1000);
    }
    private static JSONArray fetch(String query) throws Exception {
        return new JSONArray(request("https://lrclib.net/api/search?"+query,null,null,null));
    }
    static String request(String url,String referrer,String bearer,String body) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
        conn.setConnectTimeout(4500);
        conn.setReadTimeout(4500);
        conn.setRequestProperty("User-Agent","Island Android/1.0");
        if(referrer!=null)conn.setRequestProperty("Referer",referrer);
        if(bearer!=null)conn.setRequestProperty("Authorization","Bearer "+bearer);
        try {
            if(body!=null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type","application/json");
                try(OutputStream out=conn.getOutputStream()) {out.write(body.getBytes(StandardCharsets.UTF_8));}
            }
            try(InputStream in=conn.getInputStream(); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] block=new byte[4096];
                int count;
                while((count=in.read(block))!=-1) {
                    if(bytes.size()+count>2000000) throw new IOException("Lyrics response too large");
                    bytes.write(block,0,count);
                }
                return bytes.toString("UTF-8");
            }
        } finally {conn.disconnect();}
    }
    private static Lyrics best(JSONArray items,String title,String artist,long ms) {
        Lyrics best=null;
        double score=Double.MAX_VALUE;
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i);
            if(item==null || !normalize(title).equals(normalize(item.optString("trackName")))) continue;
            if(!artist.isEmpty() && !normalize(artist).equals(normalize(item.optString("artistName")))) continue;
            double gap=ms>0?Math.abs(item.optDouble("duration",0)-ms/1000.0):0;
            String timed=item.isNull("syncedLyrics")?"":item.optString("syncedLyrics");
            Lyrics candidate=new Lyrics(!timed.isEmpty() && gap<=4 ? timed :
                item.isNull("plainLyrics")?"":item.optString("plainLyrics"));
            if(candidate.text.isEmpty()) continue;
            double rank=gap+(candidate.lines.isEmpty()?5000:0);
            if(rank<score) {score=rank;best=candidate;}
        }
        return best;
    }
    static String normalize(String s){ return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]",""); }
    static String encode(String s) throws Exception {return URLEncoder.encode(s,StandardCharsets.UTF_8.name());}
}














