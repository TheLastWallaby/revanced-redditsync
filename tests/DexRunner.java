import app.revanced.patcher.patch.BytecodePatchContext;
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.BuilderExceptionHandler;
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction;
import com.android.tools.smali.dexlib2.builder.BuilderSwitchPayload;
import com.android.tools.smali.dexlib2.builder.BuilderTryBlock;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchElement;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

/**
 * Strict test runner for Sync methods in a patch context (shared by the patch suites; not a suite).
 * Sync's own methods run instruction by instruction, including instance methods of Sync objects.
 * JDK, org.json, the Android test adapters and named production helpers run on the JVM. Every
 * call is first offered to the suite's interceptor, which stands in for Android framework and app
 * services; Sync's log calls do nothing. Any other call, field or opcode fails the suite.
 */
final class DexRunner {
    /** Returned by an interceptor that leaves a call to the runner. */
    static final Object PASS = new Object();

    interface Interceptor {
        Object call(String ref, Object[] arguments);
    }

    /** An instance of one of Sync's own classes, with its fields. */
    static final class SyncObject {
        final String type;
        final Map<String, Object> fields = new HashMap<>();
        SyncObject(String type) { this.type = type; }
        @Override public String toString() { return "SyncObject(" + type + ")"; }
    }

    /** A const-class value. */
    static final class ClassRef {
        final String type;
        ClassRef(String type) { this.type = type; }
        @Override public boolean equals(Object other) { return other instanceof ClassRef && ((ClassRef) other).type.equals(type); }
        @Override public int hashCode() { return type.hashCode(); }
        @Override public String toString() { return type; }
    }

    private static final Set<String> LOGS = Set.of("Lwc/i;", "Lt7/k;");
    private static final List<String> JVM = List.of("Ljava/lang/", "Ljava/net/URI;", "Ljava/util/Locale;",
            "Ljava/util/regex/", "Ljava/util/List;", "Ljava/util/ArrayList;", "Lorg/json/", "Landroid/webkit/",
            "Landroid/text/TextUtils;", "Landroid/net/Uri;", "Landroid/view/Menu;", "Landroid/view/MenuItem;",
            "Landroid/view/SubMenu;", "Ljavax/xml/", "Lorg/w3c/dom/", "Ljava/io/ByteArrayInputStream;");

    private final BytecodePatchContext context;
    private final Interceptor interceptor;
    private final Set<String> helpers;
    private final Map<String, Object> statics = new HashMap<>();
    private final Map<String, Object> presets = new HashMap<>();
    private final Set<String> initialized = new HashSet<>();

    /** helpers: production extension classes (descriptors) that run on the JVM. */
    DexRunner(BytecodePatchContext context, Interceptor interceptor, String... helpers) {
        this.context = context;
        this.interceptor = interceptor;
        this.helpers = Set.of(helpers);
    }

    /**
     * Supplies a Sync static field (for example "Lowner;->name:Ltype;") without running its class
     * initializer, for app state the suite stands in for.
     */
    void preset(String field, Object value) {
        presets.put(field, value);
    }

    /** Runs a static Sync method by owner and name. */
    Object call(String owner, String name, Object... args) {
        ClassDef clazz = context.getClassDefs().get(owner);
        if (clazz == null) throw new AssertionError("Missing " + owner);
        for (MutableMethod m : context.proxy(clazz).getMutableClass().getMethods())
            if (m.getName().equals(name) && m.getParameterTypes().size() == args.length && (m.getAccessFlags() & 0x8) != 0)
                return run(m, args);
        throw new AssertionError("Missing " + owner + "->" + name);
    }

    /** Runs a method; for instance methods args[0] is the receiver. */
    Object run(MutableMethod method, Object[] args) {
        List<Instruction> code = new ArrayList<>(method.getImplementation().getInstructions());
        Map<Instruction, Integer> index = new IdentityHashMap<>();
        for (int n = 0; n < code.size(); n++) index.put(code.get(n), n);
        Object[] r = new Object[method.getImplementation().getRegisterCount()];
        System.arraycopy(args, 0, r, r.length - args.length, args.length);
        Object result = null;
        Throwable caught = null;
        int pc = 0, steps = 0;
        while (true) {
            if (++steps > 50000) throw new AssertionError("Unbounded execution in " + signature(method));
            Instruction i = code.get(pc);
            int next = pc + 1;
            try {
                switch (i.getOpcode()) {
                    case NOP: case CHECK_CAST: break;
                    case CONST_STRING: case CONST_STRING_JUMBO: r[a(i)] = string(i); break;
                    case CONST_4: case CONST_16: case CONST: case CONST_HIGH16:
                        r[a(i)] = ((NarrowLiteralInstruction) i).getNarrowLiteral(); break;
                    case CONST_CLASS: r[a(i)] = new ClassRef(((TypeReference) ((ReferenceInstruction) i).getReference()).getType()); break;
                    case MOVE: case MOVE_FROM16: case MOVE_16: case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16:
                        r[a(i)] = r[((TwoRegisterInstruction) i).getRegisterB()]; break;
                    case MOVE_RESULT: case MOVE_RESULT_OBJECT: r[a(i)] = result; break;
                    case MOVE_EXCEPTION: r[a(i)] = caught; break;
                    case ADD_INT_LIT8: case ADD_INT_LIT16: case XOR_INT_LIT8: case XOR_INT_LIT16: {
                        TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                        int x = (Integer) r[t.getRegisterB()], y = ((NarrowLiteralInstruction) i).getNarrowLiteral();
                        r[t.getRegisterA()] = i.getOpcode().name().startsWith("ADD") ? x + y : x ^ y;
                        break;
                    }
                    case ADD_INT_2ADDR: case SUB_INT_2ADDR: case XOR_INT_2ADDR: {
                        TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                        int x = (Integer) r[t.getRegisterA()], y = (Integer) r[t.getRegisterB()];
                        r[t.getRegisterA()] = i.getOpcode() == Opcode.ADD_INT_2ADDR ? x + y : i.getOpcode() == Opcode.SUB_INT_2ADDR ? x - y : x ^ y;
                        break;
                    }
                    case CONST_WIDE_16: case CONST_WIDE_32: case CONST_WIDE: case CONST_WIDE_HIGH16:
                        // A long in the low register; its pair register is unused.
                        r[a(i)] = ((WideLiteralInstruction) i).getWideLiteral();
                        r[a(i) + 1] = null;
                        break;
                    case INT_TO_LONG: {
                        TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                        r[t.getRegisterA()] = (long) (int) (Integer) r[t.getRegisterB()];
                        r[t.getRegisterA() + 1] = null;
                        break;
                    }
                    case ARRAY_LENGTH: r[a(i)] = Array.getLength(r[((TwoRegisterInstruction) i).getRegisterB()]); break;
                    case AGET_OBJECT: {
                        ThreeRegisterInstruction t = (ThreeRegisterInstruction) i;
                        r[t.getRegisterA()] = Array.get(r[t.getRegisterB()], (Integer) r[t.getRegisterC()]);
                        break;
                    }
                    case APUT_OBJECT: {
                        ThreeRegisterInstruction t = (ThreeRegisterInstruction) i;
                        Array.set(r[t.getRegisterB()], (Integer) r[t.getRegisterC()], r[t.getRegisterA()]);
                        break;
                    }
                    case NEW_ARRAY: {
                        TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                        String type = ((TypeReference) ((ReferenceInstruction) i).getReference()).getType();
                        Class<?> component;
                        try { component = type(type.substring(1)); }
                        catch (ClassNotFoundException syncType) { component = Object.class; }
                        r[t.getRegisterA()] = Array.newInstance(component, (Integer) r[t.getRegisterB()]);
                        break;
                    }
                    case INSTANCE_OF: {
                        TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                        String type = ((TypeReference) ((ReferenceInstruction) i).getReference()).getType();
                        r[t.getRegisterA()] = isInstance(r[t.getRegisterB()], type) ? 1 : 0;
                        break;
                    }
                    case IGET: case IGET_OBJECT: case IGET_BOOLEAN: {
                        TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                        FieldReference f = (FieldReference) ((ReferenceInstruction) i).getReference();
                        Object holder = r[t.getRegisterB()];
                        if (!(holder instanceof SyncObject)) throw new AssertionError("Field " + f + " read from " + holder);
                        r[t.getRegisterA()] = ((SyncObject) holder).fields.getOrDefault(f.getName(),
                                f.getType().startsWith("L") || f.getType().startsWith("[") ? null : 0);
                        break;
                    }
                    case IPUT: case IPUT_OBJECT: case IPUT_BOOLEAN: {
                        TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                        FieldReference f = (FieldReference) ((ReferenceInstruction) i).getReference();
                        ((SyncObject) r[t.getRegisterB()]).fields.put(f.getName(), r[t.getRegisterA()]);
                        break;
                    }
                    case NEW_INSTANCE: r[a(i)] = new Uninitialized(((TypeReference) ((ReferenceInstruction) i).getReference()).getType()); break;
                    case SGET_OBJECT: case SGET_BOOLEAN: case SGET:
                        r[a(i)] = staticField((FieldReference) ((ReferenceInstruction) i).getReference()); break;
                    case SPUT_OBJECT: case SPUT_BOOLEAN: case SPUT: {
                        FieldReference f = (FieldReference) ((ReferenceInstruction) i).getReference();
                        if (context.getClassDefs().get(f.getDefiningClass()) == null) throw new AssertionError("Write to " + f);
                        statics.put(f.toString(), r[a(i)]);
                        break;
                    }
                    case PACKED_SWITCH: case SPARSE_SWITCH: {
                        int key = (Integer) r[a(i)];
                        Instruction payload = ((BuilderOffsetInstruction) i).getTarget().getLocation().getInstruction();
                        for (SwitchElement element : ((BuilderSwitchPayload) payload).getSwitchElements()) {
                            if (element.getKey() != key) continue;
                            Integer at = index.get(((com.android.tools.smali.dexlib2.builder.instruction.BuilderSwitchElement) element)
                                    .getTarget().getLocation().getInstruction());
                            if (at == null) throw new AssertionError("Switch target not found");
                            next = at;
                        }
                        break;
                    }
                    case IF_EQZ: case IF_NEZ: case IF_LTZ: case IF_GEZ: case IF_GTZ: case IF_LEZ: {
                        Object v = r[a(i)];
                        int x = v instanceof Integer ? (Integer) v : v == null ? 0 : 1;
                        boolean jump;
                        switch (i.getOpcode()) {
                            case IF_EQZ: jump = x == 0; break;
                            case IF_NEZ: jump = x != 0; break;
                            case IF_LTZ: jump = x < 0; break;
                            case IF_GEZ: jump = x >= 0; break;
                            case IF_GTZ: jump = x > 0; break;
                            default: jump = x <= 0;
                        }
                        if (jump) next = target(index, i);
                        break;
                    }
                    case IF_EQ: case IF_NE: case IF_LT: case IF_GE: case IF_GT: case IF_LE: {
                        TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                        Object x = r[t.getRegisterA()], y = r[t.getRegisterB()];
                        int cmp = x instanceof Integer && y instanceof Integer ? Integer.compare((Integer) x, (Integer) y) : (x == y ? 0 : 1);
                        boolean jump;
                        switch (i.getOpcode()) {
                            case IF_EQ: jump = cmp == 0; break;
                            case IF_NE: jump = cmp != 0; break;
                            case IF_LT: jump = cmp < 0; break;
                            case IF_GE: jump = cmp >= 0; break;
                            case IF_GT: jump = cmp > 0; break;
                            default: jump = cmp <= 0;
                        }
                        if (jump) next = target(index, i);
                        break;
                    }
                    case GOTO: case GOTO_16: case GOTO_32: next = target(index, i); break;
                    case INVOKE_STATIC: case INVOKE_VIRTUAL: case INVOKE_DIRECT: case INVOKE_INTERFACE: case INVOKE_SUPER:
                    case INVOKE_STATIC_RANGE: case INVOKE_VIRTUAL_RANGE: case INVOKE_DIRECT_RANGE: case INVOKE_INTERFACE_RANGE:
                    case INVOKE_SUPER_RANGE:
                        result = invoke(i, r);
                        break;
                    case THROW: throw new Thrown((Throwable) r[a(i)]);
                    case RETURN: case RETURN_OBJECT: return r[a(i)];
                    case RETURN_VOID: return null;
                    default: throw new AssertionError("Unsupported opcode " + i.getOpcode() + " in " + signature(method));
                }
            } catch (Thrown thrown) {
                Integer handler = handler(method, pc, thrown.value);
                if (handler == null) throw thrown;
                caught = thrown.value;
                next = handler;
            }
            pc = next;
        }
    }

    private boolean isInstance(Object value, String type) {
        if (value == null || Integer.valueOf(0).equals(value)) return false;
        if (value instanceof SyncObject) {
            Deque<String> pending = new ArrayDeque<>(List.of(((SyncObject) value).type));
            while (!pending.isEmpty()) {
                String current = pending.pop();
                if (current.equals(type)) return true;
                ClassDef definition = context.getClassDefs().get(current);
                if (definition == null) continue;
                if (definition.getSuperclass() != null) pending.push(definition.getSuperclass());
                pending.addAll(definition.getInterfaces());
            }
            return false;
        }
        try { return type(type).isInstance(value); }
        catch (ClassNotFoundException syncType) { return false; }
    }

    private Integer handler(MutableMethod method, int pc, Throwable value) {
        for (BuilderTryBlock block : method.getImplementation().getTryBlocks()) {
            if (pc < block.start.getLocation().getIndex() || pc >= block.end.getLocation().getIndex()) continue;
            for (BuilderExceptionHandler h : block.getExceptionHandlers()) {
                String type = h.getExceptionType();
                try {
                    if (type == null || type(type).isInstance(value)) return h.getHandler().getLocation().getIndex();
                } catch (ClassNotFoundException missing) {
                    throw new AssertionError("Unknown exception type " + type);
                }
            }
        }
        return null;
    }

    private Object invoke(Instruction i, Object[] r) {
        MethodReference call = (MethodReference) ((ReferenceInstruction) i).getReference();
        int[] regs;
        if (i instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction range = (RegisterRangeInstruction) i;
            regs = new int[range.getRegisterCount()];
            for (int n = 0; n < regs.length; n++) regs[n] = range.getStartRegister() + n;
        } else {
            FiveRegisterInstruction f = (FiveRegisterInstruction) i;
            regs = Arrays.copyOf(new int[] {f.getRegisterC(), f.getRegisterD(), f.getRegisterE(), f.getRegisterF(), f.getRegisterG()},
                    f.getRegisterCount());
        }
        Object[] values = new Object[regs.length];
        for (int n = 0; n < regs.length; n++) values[n] = r[regs[n]];
        String owner = call.getDefiningClass();
        String ref = call.toString();
        Object intercepted = interceptor.call(ref, values);
        if (intercepted != PASS) return intercepted;
        if (LOGS.contains(owner)) {
            if (!call.getReturnType().equals("V")) throw new AssertionError("Sync log call returns a value: " + ref);
            return null;
        }
        boolean isStatic = i.getOpcode() == Opcode.INVOKE_STATIC || i.getOpcode() == Opcode.INVOKE_STATIC_RANGE;
        Object receiver = isStatic || values.length == 0 ? null : values[0];
        if (receiver instanceof Uninitialized && call.getName().equals("<init>")
                && context.getClassDefs().get(((Uninitialized) receiver).type) != null) {
            SyncObject made = new SyncObject(((Uninitialized) receiver).type);
            for (int n = 0; n < r.length; n++) if (r[n] == receiver) r[n] = made;
            values[0] = receiver = made;
        }
        if (receiver instanceof SyncObject) {
            if (ref.equals("Ljava/lang/Object;-><init>()V")) return null;
            boolean direct = i.getOpcode() == Opcode.INVOKE_DIRECT || i.getOpcode() == Opcode.INVOKE_DIRECT_RANGE
                    || i.getOpcode() == Opcode.INVOKE_SUPER || i.getOpcode() == Opcode.INVOKE_SUPER_RANGE;
            for (String type = direct ? owner : ((SyncObject) receiver).type; type != null; ) {
                ClassDef definition = context.getClassDefs().get(type);
                if (definition == null) break;
                MutableMethod m = find(definition, call);
                if (m != null && m.getImplementation() != null) return run(m, values);
                type = definition.getSuperclass();
            }
            throw new AssertionError("Missing Sync method " + ref + " on " + receiver);
        }
        ClassDef dexClass = context.getClassDefs().get(owner);
        if (dexClass != null && isStatic && !helpers.contains(owner)) {
            MutableMethod m = find(dexClass, call);
            if (m != null) return run(m, values);
            throw new AssertionError("Missing Sync method " + ref);
        }
        if (!helpers.contains(owner) && JVM.stream().noneMatch(owner::startsWith)) throw new AssertionError("Unexpected call " + ref);
        try {
            Class<?> type = type(owner);
            List<? extends CharSequence> params = call.getParameterTypes();
            Class<?>[] types = new Class<?>[params.size()];
            for (int n = 0; n < types.length; n++) types[n] = type(params.get(n).toString());
            // As on Android, a call on null throws NullPointerException into the method's handlers.
            if (!isStatic && !call.getName().equals("<init>") && values[0] == null)
                throw new Thrown(new NullPointerException("Call to " + ref + " on null"));
            int offset = isStatic ? 0 : 1;
            Object[] arguments = new Object[types.length];
            for (int n = 0; n < types.length; n++) arguments[n] = toJvm(values[n + offset], types[n]);
            if (call.getName().equals("<init>")) {
                Object made = type.getConstructor(types).newInstance(arguments);
                Object placeholder = values[0];
                for (int n = 0; n < r.length; n++) if (r[n] == placeholder) r[n] = made;
                return null;
            }
            Object value = type.getMethod(call.getName(), types).invoke(isStatic ? null : values[0], arguments);
            return fromJvm(value);
        } catch (InvocationTargetException thrown) {
            throw new Thrown(thrown.getCause());
        } catch (ReflectiveOperationException | IllegalArgumentException missing) {
            throw new AssertionError("Cannot call " + ref + " with " + Arrays.toString(values), missing);
        }
    }

    private MutableMethod find(ClassDef definition, MethodReference call) {
        for (MutableMethod m : context.proxy(definition).getMutableClass().getMethods())
            if (m.getName().equals(call.getName()) && m.getReturnType().equals(call.getReturnType())
                    && m.getParameterTypes().equals(call.getParameterTypes().stream().map(Object::toString).toList()))
                return m;
        return null;
    }

    private Object staticField(FieldReference field) {
        if (presets.containsKey(field.toString())) return presets.get(field.toString());
        String owner = field.getDefiningClass();
        ClassDef dexClass = context.getClassDefs().get(owner);
        if (dexClass != null) {
            if (initialized.add(owner)) {
                for (MutableMethod m : context.proxy(dexClass).getMutableClass().getMethods())
                    if (m.getName().equals("<clinit>")) run(m, new Object[0]);
            }
            if (!statics.containsKey(field.toString())) throw new AssertionError("Unset Sync static " + field);
            return statics.get(field.toString());
        }
        if (JVM.stream().noneMatch(owner::startsWith)) throw new AssertionError("Unexpected field " + field);
        try { return fromJvm(type(owner).getField(field.getName()).get(null)); }
        catch (ReflectiveOperationException missing) { throw new AssertionError("Cannot read " + field, missing); }
    }

    private static Object toJvm(Object value, Class<?> type) {
        if (type == boolean.class) return !Integer.valueOf(0).equals(value);
        if (type == char.class) return (char) (int) (Integer) value;
        // Dex writes null as the constant 0.
        if (!type.isPrimitive() && Integer.valueOf(0).equals(value) && type != Object.class && type != Integer.class) return null;
        return value;
    }
    private static Object fromJvm(Object value) {
        if (value instanceof Boolean) return (Boolean) value ? 1 : 0;
        if (value instanceof Character) return (int) (Character) value;
        return value;
    }
    private static int a(Instruction i) { return ((OneRegisterInstruction) i).getRegisterA(); }
    private static int target(Map<Instruction, Integer> index, Instruction branch) {
        Integer at = index.get(((BuilderOffsetInstruction) branch).getTarget().getLocation().getInstruction());
        if (at == null) throw new AssertionError("Branch target not found");
        return at;
    }
    static String string(Instruction i) {
        return i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof StringReference
                ? ((StringReference) ((ReferenceInstruction) i).getReference()).getString() : null;
    }
    static String signature(MutableMethod m) {
        return m.getDefiningClass() + "->" + m.getName() + "(" + String.join("", m.getParameterTypes()) + ")" + m.getReturnType();
    }
    static Class<?> type(String descriptor) throws ClassNotFoundException {
        switch (descriptor) {
            case "I": return int.class;
            case "Z": return boolean.class;
            case "C": return char.class;
            case "J": return long.class;
            case "V": return void.class;
            default:
                if (descriptor.startsWith("[")) return Class.forName(descriptor.replace('/', '.'));
                return Class.forName(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
        }
    }

    static final class Uninitialized {
        final String type;
        Uninitialized(String type) { this.type = type; }
    }
    static final class Thrown extends RuntimeException {
        final Throwable value;
        Thrown(Throwable value) { super(null, null, false, false); this.value = value; }
    }
}
