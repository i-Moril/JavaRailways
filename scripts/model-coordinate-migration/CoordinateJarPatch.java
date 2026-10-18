import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Java 17 + ASM 9. Builds a coordinate-only patch over installed plugin entry data. */
public final class CoordinateJarPatch {
    static final String RAIL = "work/art1st/javarailways/bukkit/extension/rail/";
    static final String SIGNAL = "work/art1st/javarailways/simplesignals/";
    static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
    static ClassNode parse(byte[] data) { ClassNode c = new ClassNode(); new ClassReader(data).accept(c, 0); return c; }
    static String key(MethodNode m) { return m.name + m.desc; }
    static Map<String, MethodNode> methods(ClassNode c) {
        Map<String, MethodNode> out = new TreeMap<>();
        for (MethodNode m : c.methods) require(out.put(key(m), m) == null, "Duplicate method " + key(m));
        return out;
    }
    static String value(Object o) {
        if (o instanceof Object[] a) return Arrays.stream(a).map(CoordinateJarPatch::value).toList().toString();
        return o == null ? "null" : o.getClass().getName() + ":" + o;
    }
    static int target(LabelNode label, Map<AbstractInsnNode,Integer> positions) {
        AbstractInsnNode n = label;
        while (n != null && n.getOpcode() < 0) n = n.getNext();
        return n == null ? positions.size() : positions.get(n);
    }
    static String semantic(MethodNode m) {
        Map<AbstractInsnNode,Integer> pos = new IdentityHashMap<>();
        for (AbstractInsnNode n : m.instructions) if (n.getOpcode() >= 0) pos.put(n, pos.size());
        StringBuilder s = new StringBuilder().append(m.access).append('|').append(m.signature).append('|').append(m.exceptions);
        for (AbstractInsnNode n : m.instructions) {
            if (n.getOpcode() < 0) continue;
            s.append('\n').append(n.getOpcode()).append(':');
            if (n instanceof IntInsnNode x) s.append(x.operand);
            else if (n instanceof VarInsnNode x) s.append(x.var);
            else if (n instanceof TypeInsnNode x) s.append(x.desc);
            else if (n instanceof FieldInsnNode x) s.append(x.owner).append('.').append(x.name).append(x.desc);
            else if (n instanceof MethodInsnNode x) s.append(x.owner).append('.').append(x.name).append(x.desc).append(x.itf);
            else if (n instanceof InvokeDynamicInsnNode x) s.append(x.name).append(x.desc).append(value(x.bsm)).append(value(x.bsmArgs));
            else if (n instanceof JumpInsnNode x) s.append(target(x.label,pos));
            else if (n instanceof LdcInsnNode x) s.append(value(x.cst));
            else if (n instanceof IincInsnNode x) s.append(x.var).append(',').append(x.incr);
            else if (n instanceof TableSwitchInsnNode x) { s.append(x.min).append(',').append(x.max).append(',').append(target(x.dflt,pos)); for (LabelNode l:x.labels) s.append(',').append(target(l,pos)); }
            else if (n instanceof LookupSwitchInsnNode x) { s.append(x.keys).append(':').append(target(x.dflt,pos)); for (LabelNode l:x.labels) s.append(',').append(target(l,pos)); }
            else if (n instanceof MultiANewArrayInsnNode x) s.append(x.desc).append(',').append(x.dims);
            else require(n instanceof InsnNode, "Unhandled instruction " + n.getClass());
        }
        for (TryCatchBlockNode t:m.tryCatchBlocks) s.append("\ncatch:").append(target(t.start,pos)).append(',').append(target(t.end,pos)).append(',').append(target(t.handler,pos)).append(',').append(t.type);
        return s.toString();
    }
    static byte[] fields(ClassNode source) {
        ClassNode copy=new ClassNode(); copy.version=source.version; copy.access=source.access;
        copy.name=source.name; copy.superName=source.superName; copy.signature=source.signature;
        copy.interfaces=new ArrayList<>(source.interfaces); copy.fields=new ArrayList<>(source.fields);
        ClassWriter writer=new ClassWriter(0); copy.accept(writer); return writer.toByteArray();
    }
    static void compare(byte[] old, byte[] candidate, Set<String> allowedNames, Set<String> allowedAdd, Set<String> allowedRemove) {
        ClassNode a=parse(old), b=parse(candidate);
        require(a.version==b.version && a.name.equals(b.name), "Class identity/version changed: " + a.name);
        require(Arrays.equals(fields(a),fields(b)), "Class hierarchy/fields changed: " + a.name);
        Map<String,MethodNode> am=methods(a), bm=methods(b);
        Set<String> all=new TreeSet<>(am.keySet()); all.addAll(bm.keySet());
        for(String k:all) {
            MethodNode x=am.get(k), y=bm.get(k);
            if(x==null) require(allowedAdd.contains(y.name), "Unexpected added method " + a.name + "." + k);
            else if(y==null) require(allowedRemove.contains(x.name), "Unexpected removed method " + a.name + "." + k);
            else if(!semantic(x).equals(semantic(y))) require(allowedNames.contains(x.name), "Unexpected changed method " + a.name + "." + k);
        }
    }
    static byte[] companion(byte[] old, byte[] fresh) {
        ClassNode a=parse(old), b=parse(fresh);
        String desc="(IIILorg/bukkit/block/BlockFace;F)Lwork/art1st/javarailways/simplesignals/presentation/ModelPlacement$Ground;";
        List<MethodNode> matchesA=a.methods.stream().filter(m->m.name.startsWith("groundPlacement") && m.desc.equals(desc)).toList();
        List<MethodNode> matchesB=b.methods.stream().filter(m->m.name.startsWith("groundPlacement") && m.desc.equals(desc)).toList();
        require(matchesA.size()==1 && matchesB.size()==1, "Ground overload not uniquely found");
        MethodNode source=matchesB.get(0), dest=matchesA.get(0);
        require(source.name.equals(dest.name), "Ground overload name changed");
        // Preserve baseline method metadata; replace only executable body and its debug ranges.
        dest.instructions=source.instructions; dest.tryCatchBlocks=source.tryCatchBlocks;
        dest.maxStack=source.maxStack; dest.maxLocals=source.maxLocals;
        dest.localVariables=source.localVariables;
        dest.visibleLocalVariableAnnotations=source.visibleLocalVariableAnnotations;
        dest.invisibleLocalVariableAnnotations=source.invisibleLocalVariableAnnotations;
        ClassWriter writer=new ClassWriter(0); a.accept(writer); byte[] out=writer.toByteArray();
        compare(old,out,Set.of(dest.name),Set.of(),Set.of());
        Map<String,MethodNode> output=methods(parse(out));
        for(MethodNode m:parse(old).methods) if(!key(m).equals(key(dest))) require(semantic(m).equals(semantic(output.get(key(m)))), "Other overload changed: " + key(m));
        require(semantic(source).equals(semantic(output.get(key(dest)))), "Ground body differs after serialization");
        return out;
    }
    static Map<String,byte[]> entries(Path jar) throws IOException {
        Map<String,byte[]> out=new LinkedHashMap<>();
        try(ZipFile z=new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries=z.entries();
            while(entries.hasMoreElements()) { ZipEntry e=entries.nextElement(); String n=e.getName();
                require(!n.matches("(?i)META-INF/[^/]+\\.(SF|RSA|DSA|EC)"), "Signed baseline unsupported: " + jar);
                require(!out.containsKey(n),"Duplicate ZIP entry " + n);
                try(InputStream in=z.getInputStream(e)) { out.put(n,in.readAllBytes()); }
            }
        } return out;
    }
    static byte[] needed(Map<String,byte[]> entries,String n) { byte[] b=entries.get(n); require(b!=null,"Missing entry " + n); return b; }
    static byte[] locale(byte[] baseline,String old,String replacement) {
        String text=new String(baseline,StandardCharsets.UTF_8);
        require(text.indexOf(old)>=0 && text.indexOf(old)==text.lastIndexOf(old),"Locale text must occur exactly once: " + old);
        return text.replace(old,replacement).getBytes(StandardCharsets.UTF_8);
    }
    static void write(Path output,Map<String,byte[]> baseline,Map<String,byte[]> changes) throws IOException {
        require(baseline.keySet().containsAll(changes.keySet()),"Patch would add entry");
        try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW))) {
            for(var e:baseline.entrySet()) { ZipEntry ze=new ZipEntry(e.getKey()); ze.setTime(0); z.putNextEntry(ze); z.write(changes.getOrDefault(e.getKey(),e.getValue())); z.closeEntry(); }
        }
        Map<String,byte[]> actual=entries(output);
        require(actual.keySet().equals(baseline.keySet()),"Output entry set changed");
        int modified=0;
        for(String n:actual.keySet()) {
            require(Arrays.equals(actual.get(n),changes.getOrDefault(n,baseline.get(n))),"Entry data mismatch: " + n);
            if(!Arrays.equals(actual.get(n),baseline.get(n))) { modified++; System.out.println(output.getFileName()+": " + n); }
        }
        require(modified==changes.size(),"Expected patch entry unchanged; expected " + changes.size()+", got " + modified);
        System.out.println(output.getFileName()+": verified " + modified+" modified entries; " +(actual.size()-modified)+" unchanged entries");
    }
    public static void main(String[] args) throws Exception {
        require(args.length==6,"Usage: CoordinateJarPatch host-old.jar signals-old.jar host-patch-classes-dir signals-current.jar default-rail.yml NEW-output-dir");
        Path output=Path.of(args[5]); require(!Files.exists(output),"Output directory must be new");
        Map<String,byte[]> host=entries(Path.of(args[0])), signals=entries(Path.of(args[1])), fresh=entries(Path.of(args[3]));
        Map<String,byte[]> hp=new LinkedHashMap<>(), sp=new LinkedHashMap<>();
        String ext=RAIL+"RailExtension.class", kt=RAIL+"RailExtensionKt.class";
        hp.put(ext,Files.readAllBytes(Path.of(args[2]).resolve(ext)));
        hp.put(kt,Files.readAllBytes(Path.of(args[2]).resolve(kt)));
        compare(needed(host,ext),hp.get(ext),Set.of("createDisplayVisual"),Set.of(),Set.of("computeCurveRotation"));
        compare(needed(host,kt),hp.get(kt),Set.of("computeRailDisplayPlacement","computeRailDisplayPlacement$applyLocalOffset"),Set.of("computeRailCurveRotation","computeRailDisplayPlacement$applyLocalOffset"),Set.of("computeRailDisplayPlacement$applyLocalOffset"));
        hp.put("rail_model.yml",Files.readAllBytes(Path.of(args[4])));
        String calc=SIGNAL+"presentation/ModelPlacementCalculator.class", comp=SIGNAL+"authoring/SignalWandHandler$Companion.class";
        sp.put(calc,needed(fresh,calc));
        compare(needed(signals,calc),sp.get(calc),Set.of("calculate"),Set.of(),Set.of());
        // Only the rail calculate overload may differ.
        for(MethodNode m:parse(needed(signals,calc)).methods) if(m.name.equals("calculate") && !m.desc.contains("RailAttached")) require(semantic(m).equals(semantic(methods(parse(sp.get(calc))).get(key(m)))),"Ground calculate changed");
        sp.put(comp,companion(needed(signals,comp),needed(fresh,comp)));
        sp.put("i18n/wands_en_US.properties",locale(needed(signals,"i18n/wands_en_US.properties"),"editor.placement.rail=Rail offsets: X forward / Y up / Z right (m)","editor.placement.rail=Rail offsets: X lateral / Y up / Z forward (m)"));
        sp.put("i18n/wands_zh_CN.properties",locale(needed(signals,"i18n/wands_zh_CN.properties"),"editor.placement.rail=轨道偏移：X 向前 / Y 向上 / Z 向右（米）","editor.placement.rail=轨道偏移：X 横向 / Y 向上 / Z 向前（米）"));
        Files.createDirectory(output);
        write(output.resolve("JavaRailways.jar"),host,hp);
        write(output.resolve("SimpleSignals.jar"),signals,sp);
    }
}
