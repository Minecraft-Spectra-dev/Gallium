package cn.spectra.gallium.glowoutline.shader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/** Adapts the verified binary-alpha sampling module without changing the effect body. */
public final class VisibilityGlowSource {
    private static final String CANONICAL="4de63d1643f314d020fde7ac53a0691891bb2851f570be03022ff70e682ccccd";
    private static final String LEGACY="727f5ef7e0cadfc9c5efcc0f672f347097af37d449354d3ce046a06ecd04ccb7";
    private static final Pattern COMMENTS=Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
    private static final Pattern TOKENS=Pattern.compile("[A-Za-z_]\\w*|(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?[fFuU]?|==|!=|<=|>=|&&|\\|\\||<<|>>|\\S");
    private static final Pattern IDENTIFIER=Pattern.compile("[A-Za-z_]\\w*");
    private static final Pattern MACRO=Pattern.compile("(?m)^\\h*#\\h*(define|undef)\\h+([A-Za-z_]\\w*)([^\\r\\n]*)");
    private static final Set<String> ALIASES=Set.of("ScreenSize","ShaderAlign","ShaderOffset","GalliumMaskBounds","GalliumMaskRegion","GalliumMaskStorage");
    private static final Set<String> PRIVATE_INPUTS=Set.of("MaskSampler","MaskDepthSampler","GalliumMaskStorage","maskColorFetch","maskColorGather","visibilityAlpha","visibilityMask","storedCoord","maskStoredAt","GalliumVisibilityImage","directVisibilityGrid","reconstructedVisibility","QaVisibilityFastEnabled","GalliumVisibilityGridFastEnabled");
    private static final String FAST="7a26be7b2f280e4ebaecbae9af323851f44d50587a1a6bc02828c14749863d04";
    private static final String NATIVE="8cdcafc5e47b0f129e0502987ddc55183c732118e3b5ec588d0f9960225f4aa9";
    private static final String MODULE=loadModule();
    private record Function(int start,int body,int end){}
    private VisibilityGlowSource(){}
    public static String adapt(String source){
        if(source==null || source.contains("\\") || source.contains("##"))return null;
        String visible=visible(source);
        Function stored=function(visible,"storedMask"),last=function(visible,"outlineSample");
        if(stored==null || last==null || last.end<=stored.start)return null;
        Function legacy=function(visible,"visibilityMask");
        int start=legacy==null?stored.start:legacy.start;
        if(start>stored.start)return null;
        String module=visible.substring(start,last.end),hash=digest(module);
        boolean existing=LEGACY.equals(hash) || FAST.equals(hash) || NATIVE.equals(hash);
        if(!existing && !CANONICAL.equals(hash))return null;
        var words=new HashSet<String>();var tokens=IDENTIFIER.matcher(module);
        while(tokens.find())words.add(tokens.group());
        var macros=MACRO.matcher(visible);
        while(macros.find()){
            String name=macros.group(2);if(!words.contains(name))continue;
            if(!macros.group(1).equals("define"))return null;
            if(name.equals("GALLIUM_HAS_MASK_STORAGE") || name.equals("GALLIUM_HAS_MASK_INSTANCES")){
                if(!macros.group(3).trim().equals("1"))return null;
            }else if(!ALIASES.contains(name) || !macros.group(3).trim().equals("gallium_InternalValues[gallium_InternalInstance]."+name))return null;
        }
        String outside=visible.substring(0,start)+"\n"+visible.substring(last.end);
        outside=outside.replaceAll("(?m)^\\h*#\\h*define\\h+(?:ScreenSize|ShaderAlign|ShaderOffset|GalliumMaskBounds|GalliumMaskRegion|GalliumMaskStorage)\\h+gallium_InternalValues\\[gallium_InternalInstance\\]\\.[A-Za-z_]+\\h*$","");
        outside=outside.replaceAll("\\buniform\\s+sampler2D\\s+(?:MaskSampler|MaskDepthSampler)\\s*;","");
        outside=outside.replaceAll("\\bvec4\\s+GalliumMaskStorage\\s*;","");
        if(existing){
            var image=Pattern.compile("layout\\s*\\(\\s*rg32ui\\s*,\\s*binding\\s*=\\s*0\\s*\\)\\s*readonly\\s+uniform\\s+uimage2D\\s+GalliumVisibilityImage\\s*;").matcher(outside);
            if(!image.find())return null;outside=image.replaceFirst("");
        }
        tokens=IDENTIFIER.matcher(outside);
        while(tokens.find())if(PRIVATE_INPUTS.contains(tokens.group()))return null;
        if(existing)return source;
        if(MODULE==null)return null;
        return source.substring(0,stored.start)+MODULE+source.substring(last.end);
    }
    private static String loadModule(){
        try(var stream=VisibilityGlowSource.class.getResourceAsStream("/assets/gallium/shaders/include/native_visibility_sampling.glsl")){
            return stream==null?null:new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }catch(java.io.IOException unavailable){return null;}
    }
    private static Function function(String source,String name){
        var matches=Pattern.compile("\\b(?:bool|float|vec[234]|ivec2|MaskGrid)\\s+"+name+"\\s*\\([^;{}]*\\)\\s*\\{").matcher(source);
        if(!matches.find())return null;
        int start=matches.start(),body=matches.end(),end=body,depth=1;
        while(end<source.length() && depth>0){char c=source.charAt(end++);if(c=='{')depth++;else if(c=='}')depth--;}
        if(depth!=0 || matches.find())return null;
        return new Function(start,body,end);
    }
    private static String visible(String source){
        var result=new StringBuilder(source);var matches=COMMENTS.matcher(source);
        while(matches.find())for(int i=matches.start();i<matches.end();i++)if(source.charAt(i)!='\r' && source.charAt(i)!='\n')result.setCharAt(i,' ');
        return result.toString();
    }
    private static String digest(String source){
        var tokens=new ArrayList<String>();
        for(var line:source.split("\\R")){
            var matcher=TOKENS.matcher(line);boolean any=false;
            while(matcher.find()){tokens.add(matcher.group());any=true;}
            if(any && line.stripLeading().startsWith("#"))tokens.add("@newline");
        }
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join(" ",tokens).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
