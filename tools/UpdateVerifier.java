import java.io.*;
import java.util.*;
import com.android.apksig.ApkVerifier;
import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

public class UpdateVerifier {
  static String instructionKey(Instruction i, boolean shifted) {
    StringBuilder out=new StringBuilder(i.getOpcode().toString());
    java.util.function.IntUnaryOperator reg=r->shifted && r>=13?r-1:r;
    if(i instanceof FiveRegisterInstruction) {
      var v=(FiveRegisterInstruction)i;int[] values={v.getRegisterC(),v.getRegisterD(),v.getRegisterE(),v.getRegisterF(),v.getRegisterG()};
      for(int n=0;n<v.getRegisterCount();n++)out.append(':').append(reg.applyAsInt(values[n]));
    } else if(i instanceof RegisterRangeInstruction) {
      var v=(RegisterRangeInstruction)i;out.append(':').append(reg.applyAsInt(v.getStartRegister())).append(':').append(v.getRegisterCount());
    } else {
      if(i instanceof OneRegisterInstruction)out.append(':').append(reg.applyAsInt(((OneRegisterInstruction)i).getRegisterA()));
      if(i instanceof TwoRegisterInstruction)out.append(':').append(reg.applyAsInt(((TwoRegisterInstruction)i).getRegisterB()));
      if(i instanceof ThreeRegisterInstruction)out.append(':').append(reg.applyAsInt(((ThreeRegisterInstruction)i).getRegisterC()));
    }
    if(i instanceof ReferenceInstruction)out.append(':').append(((ReferenceInstruction)i).getReference());
    if(i instanceof NarrowLiteralInstruction)out.append(':').append(((NarrowLiteralInstruction)i).getNarrowLiteral());
    return out.toString();
  }
  static Method presenterMethod(ClassDef c) {
    for(Method m:c.getMethods())if(m.getName().equals("V") && m.getParameterTypes().equals(List.of("Lxa/d;")))return m;
    throw new AssertionError("Clicked-row callback missing");
  }
  static void checkPresenter(ClassDef before, ClassDef after) {
    Method a=presenterMethod(before),b=presenterMethod(after);
    if(a.getImplementation().getRegisterCount()!=14 || b.getImplementation().getRegisterCount()!=15)throw new AssertionError("Unexpected presenter register layout");
    List<String> original=new ArrayList<>();for(Instruction i:a.getImplementation().getInstructions())original.add(instructionKey(i,false));
    List<Instruction> instructions=new ArrayList<>();b.getImplementation().getInstructions().forEach(instructions::add);
    List<String> amended=new ArrayList<>();int capture=-1,setter=-1,enqueue=-1,postLookup=-1;
    for(int n=0;n<instructions.size();n++) {
      Instruction i=instructions.get(n);String ref=i instanceof ReferenceInstruction?((ReferenceInstruction)i).getReference().toString():"";
      if(ref.equals("Lxa/d;->t0()Ljava/lang/String;")) {
        if(capture>=0 || ((FiveRegisterInstruction)i).getRegisterC()!=14)throw new AssertionError("Clicked row parent not read from original row");
        capture=n;Instruction result=instructions.get(++n);
        if(result.getOpcode()!=Opcode.MOVE_RESULT_OBJECT || ((OneRegisterInstruction)result).getRegisterA()!=12)throw new AssertionError("Parent not preserved in its own register");
        continue;
      }
      if(ref.equals("Ly8/f;->patch_setContinuationParent(Ljava/lang/String;)V")) {
        var v=(FiveRegisterInstruction)i;
        if(setter>=0 || v.getRegisterC()!=0 || v.getRegisterD()!=12)throw new AssertionError("Parent not attached to actual request");
        setter=n;continue;
      }
      if(ref.equals("Lya/d;->e0()Lxa/d;"))postLookup=n;
      if(ref.equals("Lm8/a;->c(Landroid/content/Context;Lx8/a;)V"))enqueue=n;
      amended.add(instructionKey(i,true));
    }
    if(!(capture>=0 && capture<postLookup && postLookup<setter && setter<enqueue))throw new AssertionError("Continuation metadata ordering incorrect");
    if(!original.equals(amended))throw new AssertionError("Original presenter flow changed beyond parent capture: "+original+" versus "+amended);
    System.out.println("PASS: original presenter instruction sequence preserved; clicked parent captured before register reuse and attached before enqueue");
  }
  static Map<String,ClassDef> classes(String apk) throws Exception {
    Map<String,ClassDef> out=new TreeMap<>();
    var dex=DexFileFactory.loadDexContainer(new File(apk),Opcodes.getDefault());
    for(String entry:dex.getDexEntryNames()) for(ClassDef c:dex.getEntry(entry).getDexFile().getClasses()) {
      ClassDef previous=out.putIfAbsent(c.getType(),c);
      if(previous!=null && !body(previous).equals(body(c)))throw new AssertionError("Conflicting duplicate class "+c.getType());
    }
    return out;
  }
  static String body(ClassDef c) {
    StringBuilder out=new StringBuilder();
    List<Method> methods=new ArrayList<>();c.getMethods().forEach(methods::add);
    methods.sort(Comparator.comparing(Object::toString));
    for(Method m:methods)out.append(body(m));
    return out.toString();
  }
  static String body(Method m) {
      StringBuilder out=new StringBuilder();
      out.append(m).append(m.getAccessFlags());
      var body=m.getImplementation();if(body==null)return out.toString();
      out.append(body.getRegisterCount());
      for(Instruction i:body.getInstructions()) {
        out.append(i.getOpcode());
        if(i instanceof OneRegisterInstruction)out.append('a').append(((OneRegisterInstruction)i).getRegisterA());
        if(i instanceof TwoRegisterInstruction)out.append('b').append(((TwoRegisterInstruction)i).getRegisterB());
        if(i instanceof ThreeRegisterInstruction)out.append('c').append(((ThreeRegisterInstruction)i).getRegisterC());
        if(i instanceof FiveRegisterInstruction) {
          var r=(FiveRegisterInstruction)i;
          out.append('r').append(r.getRegisterCount()).append(',').append(r.getRegisterC()).append(',').append(r.getRegisterD()).append(',').append(r.getRegisterE()).append(',').append(r.getRegisterF()).append(',').append(r.getRegisterG());
        }
        if(i instanceof RegisterRangeInstruction)out.append('s').append(((RegisterRangeInstruction)i).getStartRegister()).append(',').append(((RegisterRangeInstruction)i).getRegisterCount());
        if(i instanceof WideLiteralInstruction)out.append('l').append(((WideLiteralInstruction)i).getWideLiteral());
        if(i instanceof OffsetInstruction)out.append('o').append(((OffsetInstruction)i).getCodeOffset());
        if(i instanceof ReferenceInstruction)out.append('x').append(((ReferenceInstruction)i).getReference());
        if(i instanceof DualReferenceInstruction)out.append('y').append(((DualReferenceInstruction)i).getReference2());
        if(i instanceof SwitchPayload) for(SwitchElement e:((SwitchPayload)i).getSwitchElements())out.append('k').append(e.getKey()).append(',').append(e.getOffset());
        if(i instanceof com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload) {
          var a=(com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload)i;
          out.append(a.getElementWidth()).append(a.getArrayElements());
        }
      }
      for(var t:body.getTryBlocks()) {
        out.append(t.getStartCodeAddress()).append(',').append(t.getCodeUnitCount());
        for(var h:t.getExceptionHandlers())out.append(h.getExceptionType()).append(',').append(h.getHandlerCodeAddress());
      }
    return out.toString();
  }
  static void checkShareLinks(ClassDef before,ClassDef after) {
    Set<String> replaced=Set.of("resolveLink","getHttpURLConnection","lambda$resolveLink$4");
    Map<String,Method> original=new HashMap<>();
    before.getMethods().forEach(m->original.put(m.toString(),m));
    int changes=0;
    for(Method m:after.getMethods()) {
      Method previous=original.remove(m.toString());
      if(previous==null)throw new AssertionError("Unexpected inherited resolver method added");
      if(!replaced.contains(m.getName())) {
        if(!body(previous).equals(body(m)))throw new AssertionError("Token/pending-link lifecycle changed: "+m.getName());
        continue;
      }
      changes++;
      String helper=m.getName().equals("resolveLink")?"isShareLink":
          m.getName().equals("getHttpURLConnection")?"openConnection":"resolveOrBypass";
      boolean delegated=false;
      List<Instruction> instructions=new ArrayList<>();m.getImplementation().getInstructions().forEach(instructions::add);
      for(Instruction instruction:instructions) {
        if(!(instruction instanceof ReferenceInstruction))continue;
        String ref=((ReferenceInstruction)instruction).getReference().toString();
        if(ref.startsWith("Lapp/revanced/extension/shared/Logger;"))throw new AssertionError("Resolver still logs URL-bearing input");
        if(ref.startsWith("Lapp/revanced/extension/synccompat/SafeSLinks;->"+helper+"(")) {
          var registers=(FiveRegisterInstruction)instruction;
          if(m.getName().equals("resolveLink") &&
              (instruction!=instructions.get(0) || registers.getRegisterC()!=4 || instructions.get(2).getOpcode()!=Opcode.IF_EQZ)) {
            throw new AssertionError("Share-link validation must precede all resolver side effects");
          }
          if(m.getName().equals("getHttpURLConnection") && (registers.getRegisterC()!=1 || registers.getRegisterD()!=2))
            throw new AssertionError("Wrong URL/token at transport guard");
          if(m.getName().equals("lambda$resolveLink$4") && (registers.getRegisterC()!=4 || registers.getRegisterD()!=0))
            throw new AssertionError("Wrong URL/token at background resolver");
          delegated=true;
        }
      }
      if(!delegated)throw new AssertionError("Share-link guard missing: "+m.getName());
    }
    if(!original.isEmpty() || changes!=3)throw new AssertionError("Unexpected inherited resolver layout");
    System.out.println("PASS: share-link entry, transport and worker hardened; token/pending-link lifecycle preserved; no URL-bearing resolver logging");
  }
  public static void main(String[] args) throws Exception {
    var result=new ApkVerifier.Builder(new File(args[1])).build().verify();
    if(!result.isVerified())throw new AssertionError(result.getErrors());
    System.out.println("PASS: APK signatures verified; v2="+result.isVerifiedUsingV2Scheme()+", v3="+result.isVerifiedUsingV3Scheme());
    var before=classes(args[0]);var after=classes(args[1]);
    var newClasses=new TreeSet<>(after.keySet());newClasses.removeAll(before.keySet());
    if(!newClasses.equals(classes(args[3]).keySet()))throw new AssertionError("Unexpected added classes: "+newClasses);
    if(!after.keySet().containsAll(before.keySet()))throw new AssertionError("Existing class removed");
    List<String> changed=new ArrayList<>();
    for(String type:before.keySet())if(!body(before.get(type)).equals(body(after.get(type))))changed.add(type);
    String slink="Lapp/revanced/extension/shared/fixes/slink/BaseFixSLinksPatch;";
    if(!new TreeSet<>(changed).equals(new TreeSet<>(List.of("Ly8/q;","Ly8/f;","Lya/d$f;","Lya/d;",slink))))throw new AssertionError("Unexpected changed code: "+changed);
    System.out.println("PASS: "+before.size()+" existing classes preserved; only 5 intended classes changed; runtime classes added: "+newClasses+"");
    checkShareLinks(before.get(slink),after.get(slink));
    checkPresenter(before.get("Lya/d;"),after.get("Lya/d;"));
    ClassDef error=after.get("Lya/d$f;");
    for(Method m:error.getMethods())if(m.getName().equals("onErrorResponse")) {
      boolean shown=false;
      for(Instruction i:m.getImplementation().getInstructions()) {
        if(i instanceof ReferenceInstruction && ((ReferenceInstruction)i).getReference().toString().contains("->showCommentsError(")) {
          var argsRegisters=(FiveRegisterInstruction)i;
          if(argsRegisters.getRegisterC()!=1 || argsRegisters.getRegisterD()!=3)throw new AssertionError("Diagnostic context/error arguments incorrect");
          shown=true;break;
        }
        if(i instanceof OneRegisterInstruction && ((OneRegisterInstruction)i).getRegisterA()==3)throw new AssertionError("Error parameter overwritten before diagnostic");
      }
      if(!shown)throw new AssertionError("Diagnostic error handler missing");
    }
    System.out.println("PASS: original error reaches diagnostic handler before any parameter overwrite");
    ClassDef more=after.get("Ly8/f;");
    for(Method m:more.getMethods())if(m.getName().equals("parseNetworkResponse")) {
      boolean adapted=false;
      for(Instruction i:m.getImplementation().getInstructions()) {
        if(i instanceof ReferenceInstruction && ((ReferenceInstruction)i).getReference().toString().contains("->patch_getMoreChildrenResponse("))adapted=true;
      }
      if(!adapted)throw new AssertionError("Thread response adapter missing");
    }
    System.out.println("PASS: batch response adapter installed");
    if(args.length>2 && !args[2].equals("-")) {
      var previous=new ApkVerifier.Builder(new File(args[2])).build().verify();
      if(!previous.isVerified() || !java.util.Arrays.equals(previous.getSignerCertificates().get(0).getEncoded(),
          result.getSignerCertificates().get(0).getEncoded()))throw new AssertionError("Update signing certificate differs");
      System.out.println("PASS: signing certificate matches previous delivered APK");
    }
  }
}
