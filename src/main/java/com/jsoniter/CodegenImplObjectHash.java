package com.jsoniter;

import com.jsoniter.spi.*;

import java.util.*;

public class CodegenImplObjectHash {

    // the implementation is from dsljson, it is the fastest although has the risk not matching field strictly
    public static String genObjectUsingHash(Class clazz, ClassDescriptor desc) {
        StringBuilder lines = new StringBuilder();
        // === if null, return null
        append(lines, "if (iter.readNull()) { ");
        append(lines, "com.jsoniter.CodegenAccess.resetExistingObject(iter); return null; }");
        // === if empty, return empty
        if (desc.ctor.parameters.isEmpty()) {
            // has default ctor
            append(lines, "{{clazz}} obj = {{newInst}};");
            append(lines, "if (!com.jsoniter.CodegenAccess.readObjectStart(iter)) { return obj; }");
        } else {
            append(lines, "if (!com.jsoniter.CodegenAccess.readObjectStart(iter)) { return {{newInst}}; }");
            // ctor requires binding
            for (Binding parameter : desc.ctor.parameters) {
                appendVarDef(lines, parameter);
            }
            for (Binding field : desc.fields) {
                appendVarDef(lines, field);
            }
            for (Binding setter : desc.setters) {
                appendVarDef(lines, setter);
            }
        }
        for (WrapperDescriptor setter : desc.wrappers) {
            for (Binding param : setter.parameters) {
                appendVarDef(lines, param);
            }
        }
        // === bind fields
        HashSet<Integer> knownHashes = new HashSet<Integer>();
        HashMap<String, Binding> bindings = new HashMap<String, Binding>();
        for (Binding binding : desc.allDecoderBindings()) {
            for (String fromName : binding.fromNames) {
                bindings.put(fromName, binding);
            }
        }
        ArrayList<String> fromNames = new ArrayList<String>(bindings.keySet());
        Collections.sort(fromNames, new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                int x = calcHash(o1);
                int y = calcHash(o2);
                return (x < y) ? -1 : ((x == y) ? 0 : 1);
            }
        });
        // === bind more fields
        append(lines, "do {");
        append(lines, "switch (com.jsoniter.CodegenAccess.readObjectFieldAsHash(iter)) {");
        for (String fromName : fromNames) {
            int intHash = calcHash(fromName);
            if (intHash == 0) {
                // hash collision, 0 can not be used as sentinel
                return CodegenImplObjectStrict.genObjectUsingStrict(clazz, desc);
            }
            if (knownHashes.contains(intHash)) {
                // hash collision with other field can not be used as sentinel
                return CodegenImplObjectStrict.genObjectUsingStrict(clazz, desc);
            }
            knownHashes.add(intHash);
            append(lines, "case " + intHash + ": ");
            appendBindingSet(lines, desc, bindings.get(fromName));
            append(lines, "continue;");
        }
        append(lines, "}");
        append(lines, "iter.skip();");
        append(lines, "} while (com.jsoniter.CodegenAccess.nextToken(iter) == ',');");
        if (!desc.ctor.parameters.isEmpty()) {
            append(lines, CodegenImplNative.getTypeName(clazz) + " obj = {{newInst}};");
            for (Binding field : desc.fields) {
                append(lines, String.format("obj.%s = _%s_;", field.field.getName(), field.name));
            }
            for (Binding setter : desc.setters) {
                append(lines, String.format("obj.%s(_%s_);", setter.method.getName(), setter.name));
            }
        }
        appendWrappers(desc.wrappers, lines);
        append(lines, "return obj;");
        return lines.toString()
                .replace("{{clazz}}", clazz.getCanonicalName())
                .replace("{{newInst}}", genNewInstCode(clazz, desc.ctor));
    }

    public static int calcHash(String fromName) {
        long hash = 0x811c9dc5;
        for (byte b : fromName.getBytes()) {
            hash ^= b;
            hash *= 0x1000193;
        }
        return (int) hash;
    }

    private static void appendBindingSet(StringBuilder lines, ClassDescriptor desc, Binding binding) {
        if (desc.ctor.parameters.isEmpty() && (desc.fields.contains(binding) || desc.setters.contains(binding))) {
            if (binding.valueCanReuse) {
                append(lines, String.format("com.jsoniter.CodegenAccess.setExistingObject(iter, obj.%s);", binding.field.getName()));
            }
            if (binding.field != null) {
                append(lines, String.format("obj.%s = %s;", binding.field.getName(), CodegenImplNative.genField(binding)));
            } else {
                append(lines, String.format("obj.%s(%s);", binding.method.getName(), CodegenImplNative.genField(binding)));
            }
        } else {
            append(lines, String.format("_%s_ = %s;", binding.name, CodegenImplNative.genField(binding)));
        }
    }

    static void appendWrappers(List<WrapperDescriptor> wrappers, StringBuilder lines) {
        for (WrapperDescriptor wrapper : wrappers) {
            lines.append("obj.");
            lines.append(wrapper.method.getName());
            appendInvocation(lines, wrapper.parameters);
            lines.append(";\n");
        }
    }

    static void appendVarDef(StringBuilder lines, Binding parameter) {
        String typeName = CodegenImplNative.getTypeName(parameter.valueType);
        append(lines, String.format("%s _%s_ = %s;", typeName, parameter.name, CodegenImplObjectStrict.DEFAULT_VALUES.get(typeName)));
    }

    static String genNewInstCode(Class clazz, ConstructorDescriptor ctor) {
        StringBuilder code = new StringBuilder();
        if (ctor.parameters.isEmpty()) {
            // nothing to bind, safe to reuse existing object
            code.append("(com.jsoniter.CodegenAccess.existingObject(iter) == null ? ");
        }
        if (ctor.objectFactory != null) {
            code.append(String.format("(%s)com.jsoniter.spi.JsoniterSpi.create(%s.class)",
                    clazz.getCanonicalName(), clazz.getCanonicalName()));
        } else {
            if (ctor.staticMethodName == null) {
                code.append(String.format("new %s", clazz.getCanonicalName()));
            } else {
                code.append(String.format("%s.%s", clazz.getCanonicalName(), ctor.staticMethodName));
            }
        }
        List<Binding> params = ctor.parameters;
        if (ctor.objectFactory == null) {
            appendInvocation(code, params);
        }
        if (ctor.parameters.isEmpty()) {
            // nothing to bind, safe to reuse existing obj
            code.append(String.format(" : (%s)com.jsoniter.CodegenAccess.resetExistingObject(iter))", clazz.getCanonicalName()));
        }
        return code.toString();
    }

    private static void appendInvocation(StringBuilder code, List<Binding> params) {
        code.append("(");
        boolean isFirst = true;
        for (Binding ctorParam : params) {
            if (isFirst) {
                isFirst = false;
            } else {
                code.append(",");
            }
            code.append(String.format("_%s_", ctorParam.name));
        }
        code.append(")");
    }

    static void append(StringBuilder lines, String str) {
        lines.append(str);
        lines.append("\n");
    }

}
