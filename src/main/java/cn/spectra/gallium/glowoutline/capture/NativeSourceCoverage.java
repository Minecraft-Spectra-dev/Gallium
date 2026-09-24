package cn.spectra.gallium.glowoutline.capture;
//#if MC==1_21_11 || MC==1_26_01
import com.mojang.blaze3d.systems.RenderSystem;
/** Frame-local original-submission ownership; capture mirrors never enter the registration scope. */
public final class NativeSourceCoverage implements AutoCloseable {
    private static NativeSourceCoverage current;
    private static Submission collecting;
    private final SourceSubmissionLedger ledger=new SourceSubmissionLedger(128,4096);
    private boolean closed;
    private NativeSourceCoverage(){current=this;}
    public static NativeSourceCoverage beginFrame(){return current==null && RenderSystem.isOnRenderThread()?new NativeSourceCoverage():null;}
    public static boolean active(){return current!=null && !current.closed;}
    public static Submission beginSubmission(GlowCaptureState owner){
        if(!active())cn.spectra.gallium.glowoutline.shader.NativeWorldVisibility.beginSource(owner);
        if(!active() || owner==null || owner.firstPerson)return null;
        return new Submission(current,owner);
    }
    public static final class Submission implements AutoCloseable {
        private final NativeSourceCoverage frame;
        private final GlowCaptureState owner;
        private final Submission parent;
        private Object source;private int count;private boolean completed,closed;
        private Submission(NativeSourceCoverage frame,GlowCaptureState owner){
            this.frame=frame;this.owner=owner;parent=collecting;collecting=this;
            if(parent!=null){frame.ledger.reject(owner);frame.ledger.reject(parent.owner);}
        }
        public void complete(){completed=true;}
        @Override public void close(){
            if(closed)return;closed=true;
            if(collecting!=this){frame.ledger.reject(owner);return;}
            collecting=parent;
            if(!completed || count!=1 || frame!=current || frame.closed || !cn.spectra.gallium.glowoutline.shader.NativeWorldVisibility.sourceAllowed(source))frame.ledger.reject(owner);
            else frame.ledger.register(owner,source);
            source=null;
        }
    }
    public static void registered(Object source){var scope=collecting;if(scope!=null){scope.count++;scope.source=source;}}
    public static GlowCaptureState owner(Object source){var frame=current;return frame==null?null:(GlowCaptureState)frame.ledger.owner(source);}
    public static void observed(Object source){var frame=current;if(frame!=null && collecting==null)frame.ledger.observed(source);}
    public static void reject(GlowCaptureState owner){var frame=current;if(frame!=null && owner!=null && !owner.firstPerson)frame.ledger.reject(owner);}
    public static boolean complete(GlowCaptureState owner){var frame=current;return frame!=null && frame.ledger.complete(owner);}
    public static long revision(){var frame=current;return frame==null?-1:frame.ledger.revision();}
    public static int expected(GlowCaptureState owner){var frame=current;return frame==null?0:frame.ledger.expected(owner);}
    public static int observedCount(GlowCaptureState owner){var frame=current;return frame==null?0:frame.ledger.observedCount(owner);}
    public static void finishFrame(){if(current!=null)current.close();}
    @Override public void close(){if(closed)return;closed=true;ledger.close();if(current==this){current=null;collecting=null;}}
}
//#else
//$$ public final class NativeSourceCoverage {}
//#endif
