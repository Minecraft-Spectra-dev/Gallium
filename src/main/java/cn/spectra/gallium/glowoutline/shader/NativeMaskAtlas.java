package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_21_06 && MC<1_26_02
import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.capture.*;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import org.lwjgl.opengl.*;
import java.util.ArrayList;

/** Copies complete native mask regions, then consumes them in their original blend order. */
final class NativeMaskAtlas implements AutoCloseable {
    private static TextureTarget storage;
    //#if MC<1_26_01
    //$$ private static TextureTarget fallbackStorage;
    //#endif
    private static Entry drawing;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final RenderTarget output;
    private final GlowComposite.LateCompositeFrame composite;
    private final OpenGlMaskOrdering.Stamp ordering;
    private final long epoch;
    private MaskAtlasLayout layout;
    private TextureTarget page;
    private boolean unavailable;

    static { GlowResources.register(NativeMaskAtlas::disposeStorage); }

    private static void disposeStorage() {
        if (storage != null) storage.destroyBuffers();
        storage = null; drawing = null;
        //#if MC<1_26_01
        //$$ if(fallbackStorage!=null)fallbackStorage.destroyBuffers();fallbackStorage=null;
        //#endif
    }

    static long reservedBytes() {
        long bytes = storage == null ? 0L : (long) storage.width * storage.height * 8L;
        //#if MC<1_26_01
        //$$ bytes += fallbackStorage == null ? 0L : (long) fallbackStorage.width * fallbackStorage.height * 8L;
        //#endif
        return bytes;
    }

    static void beginFrame(boolean ordinary, int width, int height) {
        if (storage != null && (!ordinary || storage.width != powerOfTwo(width)
                || storage.height != powerOfTwo(height)
                || !GlowCaptureManager.maskAtlasFitsBudget(width, height, storage.width, storage.height)))
            disposeStorage();
    }

    NativeMaskAtlas(RenderTarget output, GlowComposite.LateCompositeFrame composite, long epoch) {
        this.output = output; this.composite = composite; this.epoch = epoch;
        ordering = OpenGlMaskOrdering.observe();
    }

    record Entry(GlowCaptureState state, RenderTarget output, TextureTarget nativeMask,
                 TextureTarget atlas, TextureTarget baseDepth, long epoch,
                 int x, int y, int width, int height, int offsetX, int offsetY, int slot,
                 MaskStorageImage visibility) {}

    static Entry current(GlowCaptureState state, RenderTarget output) {
        var entry = drawing;
        return entry != null && entry.state == state && entry.output == output
                && state.hasOrdinaryMaskStored(entry.epoch, entry) ? entry : null;
    }

    //#if MC==1_21_11
    //$$ /** Reuse only the native masks already owned by this frame's SR history capture. */
    //$$ private Entry srEntry(GlowCaptureState state) {
    //$$     var entry=OutlineSrCapture.latest(state);
    //$$     return entry!=null && entry.output()==output && entry.epoch()==epoch
    //$$             && entry.baseDepth()==null && state.maskDepthSnapshotGeneration<0
    //$$             && state.lastMaskScaleX==1 && state.lastMaskScaleY==1
    //$$             && state.lastMaskOffsetX==0 && state.lastMaskOffsetY==0
    //$$             && OriginalGlowParameters.supportsDeferredSceneOcclusion(state.config)
    //$$             && GlowCaptureManager.srStoredFallbackFitsBudget(output.width,output.height)
    //$$             ? entry : null;
    //$$ }
    //$$ private boolean prepareSrFallback(TextureTarget mask) {
    //$$     if(fallbackStorage==null || fallbackStorage.width!=output.width || fallbackStorage.height!=output.height) {
    //$$         if(fallbackStorage!=null)fallbackStorage.destroyBuffers();fallbackStorage=null;
    //$$         fallbackStorage=new TextureTarget("GlowSrAtlasFallback",output.width,output.height,true);
    //$$     }
    //$$     return !fallbackStorage.getColorTexture().isClosed() && !fallbackStorage.getDepthTexture().isClosed()
    //$$             && fallbackStorage.getColorTexture().getFormat()==mask.getColorTexture().getFormat()
    //$$             && fallbackStorage.getDepthTexture().getFormat()==mask.getDepthTexture().getFormat();
    //$$ }
    //#endif
    boolean supports(GlowCaptureState state) {
        var contract = state.config == null ? null : BoundedGlowContracts.get(state.config.shader());
        boolean iris = IrisCompat.isShaderActive();
        //#if MC==1_21_11
        //$$ if(srEntry(state)!=null) return !unavailable && ordering!=null && ordering.current()
        //$$         && state.guiEntity==null && !state.superResolutionPrepared
        //$$         && contract!=null && contract.atlasStorage() && state.config.params().size()<=252;
        //#endif
        boolean mappedOriginal = false;
        //#if MC==1_21_08
        //$$ mappedOriginal = iris && OriginalGlowParameters.supportsDeferredSceneOcclusion(state.config)
        //$$         && !state.maskDepthPrepared && state.maskDepthSnapshotGeneration < 0;
        //#elseif MC==1_21_10 || MC==1_21_11 || MC==1_26_01
        mappedOriginal = iris && OriginalGlowParameters.supportsDeferredSceneOcclusion(state.config)
                && GlowCaptureManager.storedPooledMaskBase(state) != null;
        //#endif
        return !unavailable && ordering != null && ordering.current()
                && (!iris || mappedOriginal) && !IrisCompat.isActiveSrRuntime()
                && state.guiEntity == null && !state.superResolutionPrepared && contract != null
                // A conservative bound preserves every existing std140 field without overflow.
                && state.config.params().size() <= 252
                && state.maskBounds.valid() && contract.atlasStorage()
                && (mappedOriginal || (state.lastMaskScaleX == 1 && state.lastMaskScaleY == 1
                && state.lastMaskOffsetX == 0 && state.lastMaskOffsetY == 0));
    }

    /** Null leaves the borrowed mask untouched so the caller can flush and retry. */
    Object copy(GlowCaptureState state, TextureTarget mask) {
        if (!supports(state) || entries.size() >= 256) return null;
        //#if MC==1_21_11
        //$$ var sr=srEntry(state);
        //$$ if(sr!=null) {
        //$$     try { if(!prepareSrFallback(mask))return null; }
        //$$     catch(RuntimeException allocationFailure){unavailable=true;return null;}
        //$$     var entry=new Entry(state,output,mask,sr.atlas(),null,epoch,
        //$$             sr.x(),sr.y(),sr.width(),sr.height(),sr.offsetX(),sr.offsetY(),entries.size(),null);
        //$$     entries.add(entry);return entry;
        //$$ }
        //#endif
        //#if MC==1_21_11 || MC==1_26_01
        MaskStorageImage visibility=NativeWorldVisibility.lease(state);
        //#else
        //$$ MaskStorageImage visibility=null;
        //#endif
        if (visibility==null && !ensureStorage(mask)) return null;
        boolean clearedBase = state.firstPerson;
        //#if MC==1_21_08
        //$$ clearedBase |= IrisCompat.isShaderActive() && !state.maskDepthPrepared
        //$$         && state.maskDepthSnapshotGeneration < 0
        //$$         && OriginalGlowParameters.supportsDeferredSceneOcclusion(state.config);
        //#endif
        TextureTarget base = clearedBase ? null : GlowCaptureManager.getSceneDepthTarget();
        //#if MC==1_21_10 || MC==1_21_11 || MC==1_26_01
        if (IrisCompat.isShaderActive()) base = GlowCaptureManager.storedPooledMaskBase(state);
        //#endif
        if (!clearedBase && (base == null || base.width != output.width || base.height != output.height
                || base.getDepthTextureView() == null)) return null;
        int x = edge(state.maskBounds.minX(), output.width), y = edge(state.maskBounds.minY(), output.height);
        int w = Math.max(0, edge(state.maskBounds.maxX(), output.width) - x);
        int h = Math.max(0, edge(state.maskBounds.maxY(), output.height) - y);
        if (visibility!=null) {
            var entry=new Entry(state,output,mask,mask,base,epoch,x,y,w,h,visibility.id(),0,entries.size(),visibility);
            entries.add(entry);return entry;
        }
        var tile = layout.place(w, h);
        if (tile == null) return null;
        if (w != 0 && h != 0) {
            copyRegion((GlTexture) mask.getColorTexture(), (GlTexture) page.getColorTexture(), x, y, tile);
            copyRegion((GlTexture) mask.getDepthTexture(), (GlTexture) page.getDepthTexture(), x, y, tile);
        }
        var entry = new Entry(state, output, mask, page, base, epoch, x, y, w, h, tile.x() - x, tile.y() - y, entries.size(),null);
        entries.add(entry);
        //#if MC==1_21_08 || MC==1_21_11
        //$$ OutlineTemporalStabilizer.stored(entry);
        //#endif
        return entry;
    }

    private static int edge(float value, int size) { return (int) Math.max(0, Math.min(size, value)); }

    private static void copyRegion(GlTexture from, GlTexture to, int x, int y, MaskAtlasLayout.Tile tile) {
        GL43.glCopyImageSubData(from.glId(), GL11.GL_TEXTURE_2D, 0, x, y, 0,
                to.glId(), GL11.GL_TEXTURE_2D, 0, tile.x(), tile.y(), 0, tile.width(), tile.height(), 1);
    }

    private boolean ensureStorage(TextureTarget mask) {
        if (page != null) return page == storage && !page.getColorTexture().isClosed()
                && !page.getDepthTexture().isClosed();
        var caps = GL.getCapabilities();
        if ((!caps.OpenGL43 && !caps.GL_ARB_copy_image)
                || !(mask.getColorTexture() instanceof GlTexture) || !(mask.getDepthTexture() instanceof GlTexture)) {
            unavailable = true; return false;
        }
        int w = powerOfTwo(output.width), h = powerOfTwo(output.height);
        int limit = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        if (w <= 0 || h <= 0 || w > limit || h > limit
                || !GlowCaptureManager.maskAtlasFitsBudget(output.width, output.height, w, h)) {
            unavailable = true; return false;
        }
        try {
            if (storage != null && (storage.width != w || storage.height != h)) {
                storage.destroyBuffers(); storage = null;
            }
            if (storage == null) storage = new TextureTarget("GlowMaskAtlas", w, h, true);
            if (!(storage.getColorTexture() instanceof GlTexture) || !(storage.getDepthTexture() instanceof GlTexture)
                    || storage.getColorTexture().getFormat() != mask.getColorTexture().getFormat()
                    || storage.getDepthTexture().getFormat() != mask.getDepthTexture().getFormat()) {
                unavailable = true; return false;
            }
            //#if MC<1_26_01
            //$$ if(fallbackStorage==null || fallbackStorage.width!=output.width || fallbackStorage.height!=output.height) {
            //$$     if(fallbackStorage!=null)fallbackStorage.destroyBuffers();
            //$$     fallbackStorage=new TextureTarget("GlowMaskAtlasFallback",output.width,output.height,true);
            //$$ }
            //$$ if(fallbackStorage.getColorTexture().isClosed() || fallbackStorage.getDepthTexture().isClosed()) {
            //$$     unavailable=true;return false;
            //$$ }
            //#endif
            page = storage; layout = new MaskAtlasLayout(w, h); return true;
        } catch (RuntimeException failed) { unavailable = true; return false; }
    }

    //#if MC<1_26_01
    //$$ /** Scalar fallback uses the original physical grid, avoiding atlas gather rounding. */
    //$$ private TextureTarget materialize(Entry entry) {
    //$$     var encoder=com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
    //$$     if(fallbackStorage==null || fallbackStorage.width!=output.width || fallbackStorage.height!=output.height)
    //$$         throw new IllegalStateException("Atlas fallback was not reserved before capture");
    //$$     encoder.clearColorAndDepthTextures(fallbackStorage.getColorTexture(),0,fallbackStorage.getDepthTexture(),1.0);
    //$$     if(entry.baseDepth()!=null)encoder.copyTextureToTexture(entry.baseDepth().getDepthTexture(),
    //$$             fallbackStorage.getDepthTexture(),0,0,0,0,0,output.width,output.height);
    //$$     if(entry.width()!=0 && entry.height()!=0) {
    //$$         GL43.glCopyImageSubData(((GlTexture)entry.atlas().getColorTexture()).glId(),GL11.GL_TEXTURE_2D,0,
    //$$                 entry.x()+entry.offsetX(),entry.y()+entry.offsetY(),0,
    //$$                 ((GlTexture)fallbackStorage.getColorTexture()).glId(),GL11.GL_TEXTURE_2D,0,
    //$$                 entry.x(),entry.y(),0,entry.width(),entry.height(),1);
    //$$         GL43.glCopyImageSubData(((GlTexture)entry.atlas().getDepthTexture()).glId(),GL11.GL_TEXTURE_2D,0,
    //$$                 entry.x()+entry.offsetX(),entry.y()+entry.offsetY(),0,
    //$$                 ((GlTexture)fallbackStorage.getDepthTexture()).glId(),GL11.GL_TEXTURE_2D,0,
    //$$                 entry.x(),entry.y(),0,entry.width(),entry.height(),1);
    //$$     }
    //$$     return fallbackStorage;
    //$$ }
    //#endif

    private static int powerOfTwo(int size) {
        return size <= 0 || size > (1 << 29) ? 0 : size == 1 ? 1 : Integer.highestOneBit(size - 1) << 1;
    }

    boolean flush() {
        if (entries.isEmpty()) return true;
        try (var instances = new NativeGlowInstances()) {
        composite.prepareStoredUniforms(entries);
        for (int index = 0; index < entries.size();) {
            var entry = entries.get(index);
            if (ordering == null || !ordering.current() || !composite.valid()
                    || !entry.state.hasOrdinaryMaskStored(epoch, entry)) return false;
            int grouped = composite.compositeStoredInstances(instances, entries, index);
            if (grouped > 0) {
                for (int i = 0; i < grouped; i++) {
                    var consumed = entries.get(index + i);
                    consumed.state.forgetOrdinaryMaskStorage(consumed);
                }
                index += grouped;
                continue;
            }
            if (entry.visibility()!=null) return false;
            var previous = drawing;
            //#if MC>=1_26_01
            drawing = entry;
            try {
                if (!composite.compositeOrdinaryState(entry.state, entry.nativeMask)) return false;
            } finally { drawing = previous; }
            //#else
            //$$ drawing=null;
            //$$ try {
            //$$     if(!composite.compositeOrdinaryState(entry.state,materialize(entry)))return false;
            //$$ } finally {drawing=previous;}
            //#endif
            entry.state.forgetOrdinaryMaskStorage(entry);
            index++;
        }
        entries.clear();
        if (layout != null) layout.reset();
        return true;
        } finally { composite.finishStoredUniforms(); }
    }

    @Override public void close() {
        for (var entry : entries) entry.state.forgetOrdinaryMaskStorage(entry);
        entries.clear();
    }
}
//#else
//$$ final class NativeMaskAtlas { private NativeMaskAtlas() {} }
//#endif
