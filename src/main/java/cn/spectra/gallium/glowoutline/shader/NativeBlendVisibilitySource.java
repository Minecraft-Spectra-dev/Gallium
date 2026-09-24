package cn.spectra.gallium.glowoutline.shader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
/** Exact native 1.21.11 item program: surviving normalized alpha is at least 0.1.
 * With source-alpha ONE and destination-alpha ONE_MINUS_SRC_ALPHA, every surviving
 * fragment leaves RGBA8 alpha above the binary mask threshold, regardless of overlap.
 * Callers must additionally prove normalized texture/vertex/modulator alpha.
 */
public final class NativeBlendVisibilitySource {
 private static final String VERIFIED="70541d2096213edfc62eb37899dd460a0b2ce35b1a1a17cb1118e7bb2d13e822";
 private static final Pattern COMMENTS=Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
 private static final Pattern METADATA=Pattern.compile("(?m)^\\h*#\\h*(?:line\\h+\\d+(?:\\h+\\d+)?|version\\h+\\d+(?:\\h+core)?)\\h*$");
 private static final Pattern TOKENS=Pattern.compile("[A-Za-z_]\\w*|(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?[fFuU]?|==|!=|<=|>=|&&|\\|\\||<<|>>|\\S");
 private NativeBlendVisibilitySource(){}
 public static boolean verified(String source){
  if(source==null || source.contains("\\") || source.contains("##"))return false;
  String visible=METADATA.matcher(COMMENTS.matcher(source).replaceAll(" ")).replaceAll("");
  if(visible.indexOf('#')>=0)return false;
  var matcher=TOKENS.matcher(visible);var tokens=new java.util.ArrayList<String>();
  while(matcher.find())tokens.add(matcher.group());
  try{return VERIFIED.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join(" ",tokens).getBytes(StandardCharsets.UTF_8))));}
  catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
 }
}
