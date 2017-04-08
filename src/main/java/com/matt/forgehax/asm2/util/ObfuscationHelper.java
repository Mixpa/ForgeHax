package com.matt.forgehax.asm2.util;

import bspkrs.mmv.*;
import com.fr1kin.asmhelper.types.ASMClass;
import com.fr1kin.asmhelper.types.ASMField;
import com.fr1kin.asmhelper.types.ASMMethod;
import com.google.common.base.Strings;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/**
 * Created on 1/12/2017 by fr1kin
 */
public class ObfuscationHelper {
    private static ObfuscationHelper instance = null;

    public static ObfuscationHelper getOrCreateInstanceOf(boolean obfuscated, Logger logger) {
        if(Objects.isNull(instance)) {
            try {
                instance = obfuscated ? new ObfuscationHelper(logger) : new ObfuscationHelperPass(logger);
            } catch (Exception e) {
                logger.error(e.getMessage());
            }
        }
        return instance;
    }

    public static ObfuscationHelper getInstance() {
        return instance;
    }

    private final Logger logger;

    private final BiMap<String, String> mcClasses;

    private final Map<String, Map<String, McpTypeData>> mcpMethodData;
    private final Map<String, Map<String, McpTypeData>> mcpFieldData;

    protected ObfuscationHelper(Logger logger) throws IOException {
        this.logger = logger;
        MCPMappingLoader mcpMappingLoader = new MCPMappingLoader();

        startupMsg();

        this.mcClasses = ImmutableBiMap.copyOf(ReflectionHelper.getPrivateValue(
                FMLDeobfuscatingRemapper.class,
                FMLDeobfuscatingRemapper.INSTANCE,
                "classNameBiMap"
        ));

        this.mcpMethodData = buildMcpTypeData(
                mcpMappingLoader.getCsvMethodData(),
                mcpMappingLoader.getSrgFileData().class2MethodDataSet,
                getConvertedMap(
                        ReflectionHelper.getPrivateValue(FMLDeobfuscatingRemapper.class, FMLDeobfuscatingRemapper.INSTANCE, "rawMethodMaps"),
                        str -> str.split("\\(")[0]
                ),
                ((csvData, data) -> csvData.getMcpName() + data.getSrgDescriptor())
        );
        this.mcpFieldData = buildMcpTypeData(
                mcpMappingLoader.getCsvFieldData(),
                mcpMappingLoader.getSrgFileData().class2FieldDataSet,
                getConvertedMap(
                        ReflectionHelper.getPrivateValue(FMLDeobfuscatingRemapper.class, FMLDeobfuscatingRemapper.INSTANCE, "rawFieldMaps"),
                        str -> str.split(":")[0]
                ),
                ((csvData, data) -> csvData.getMcpName())
        );
    }

    public Logger getLogger() {
        return logger;
    }

    protected void startupMsg() {
        getLogger().info("initializing ObfuscationHelper WITH obfuscation");
    }

    public boolean isObfuscated() {
        return true;
    }

    protected Map<String, String> getMcClasses() {
        return Collections.unmodifiableMap(mcClasses);
    }

    public Map<String, Map<String, McpTypeData>> getMcpFieldData() {
        return mcpFieldData;
    }

    public Map<String, Map<String, McpTypeData>> getMcpMethodData() {
        return mcpMethodData;
    }

    protected String getClassName(String className, Map<String, String> map) {
        // mcp map -> obf name
        String name = map.get(className);
        if(Strings.isNullOrEmpty(name)) {
            getLogger().warn("Could not lookup name for class '" + className + "'");
            return className;
        } else return name;
    }

    public String getObfClassName(String className) {
        return getClassName(className, mcClasses.inverse());
    }

    public String getObfClassName(ASMClass clazz) {
        return getObfClassName(clazz.getName());
    }

    public String getMcpClassName(String className) {
        return getClassName(className, mcClasses);
    }

    public String getMcpClassName(ASMClass clazz) {
        return getClassName(clazz.getName(), mcClasses);
    }

    public String getSrgMethodName(String parentClassName, String methodName, String methodDescriptor) {
        try {
            return getMcpMethodData().get(parentClassName).get(methodName + methodDescriptor).getSrgName();
        } catch (Exception e) {
            getLogger().warn("Could not lookup srg name for method '" + parentClassName + "::" + methodName + methodDescriptor + "'");
            return methodName;
        }
    }

    public String getObfMethodName(String parentClassName, String methodName, String methodDescriptor) {
        try {
            return getMcpMethodData().get(parentClassName).get(methodName + methodDescriptor).getObfName();
        } catch (Exception e) {
            getLogger().warn("Could not lookup obf name for method '" + parentClassName + "::" + methodName + methodDescriptor + "'");
            return methodName;
        }
    }

    public String getObfMethodName(ASMMethod method) {
        return getObfMethodName(method.getParentClass().getName(), method.getName(), method.getDescriptor());
    }

    public String getSrgFieldName(String parentClassName, String fieldName) {
        try {
            return getMcpFieldData().get(parentClassName).get(fieldName).getSrgName();
        } catch (Exception e) {
            getLogger().warn("Could not lookup srg name for field '" + parentClassName + "." + fieldName + "'");
            return fieldName;
        }
    }

    public String getObfFieldName(String parentClassName, String fieldName) {
        try {
            return getMcpFieldData().get(parentClassName).get(fieldName).getObfName();
        } catch (Exception e) {
            getLogger().warn("Could not lookup obf name for field '" + parentClassName + "." + fieldName + "'");
            return fieldName;
        }
    }

    public String getObfFieldName(ASMField field) {
        return getObfFieldName(field.getParentClass().getName(), field.getName());
    }

    public Type translateMethodType(Type methodType) {
        Type[] translated = translateTypes(mcClasses, Lists.asList(methodType.getReturnType(), methodType.getArgumentTypes()).toArray(new Type[] {}));
        return Type.getMethodType(translated[0], Arrays.copyOfRange(translated, 1, translated.length));
    }

    public Type translateFieldType(Type fieldType) {
        return translateTypes(mcClasses, fieldType)[0];
    }

    private Type[] translateTypes(Map<String, String> mapIn, Type... types) {
        int index = 0;
        Type[] translated = new Type[types.length];
        for(Type arg : types) {
            switch (arg.getSort()) {
                case Type.ARRAY:
                    // ignore primitive arrays
                    if(arg.getElementType().getSort() != Type.OBJECT) break;
                case Type.OBJECT:
                    String desc = arg.getDescriptor();
                    String heading = desc.substring(0, desc.indexOf('L') + 1);
                    String name = desc.substring(heading.length(), desc.indexOf(';'));
                    String newName = mapIn.get(name);
                    arg = Type.getType(heading + (!Strings.isNullOrEmpty(newName) ? newName : name) + ";");
                    break;
                default:
                    break;
            }
            translated[index++] = arg;
        }
        return translated;
    }

    private Map<String, Map<String, String>> getConvertedMap(Map<String, Map<String, String>> mapIn, Function<String,String> getNameFunction) {
        Map<String, Map<String, String>> mapOut = Maps.newHashMap();
       mapIn.entrySet().forEach(entry -> {
            String realName = getMcpClassName(entry.getKey());
            if(!Strings.isNullOrEmpty(realName)) {
                Map<String, String> subMap = Maps.newHashMap();
                entry.getValue().entrySet().forEach(subEntry -> {
                    String key = getNameFunction.apply(subEntry.getKey());
                    String value = getNameFunction.apply(subEntry.getValue());
                    subMap.put(
                            isObfuscated() ? value : key,
                            isObfuscated() ? key : value
                    );
                });
                mapOut.put(realName, Collections.unmodifiableMap(subMap));
            }
        });
        return mapOut;
    }

    private static <E extends MemberSrgData> Map<String, Map<String, McpTypeData>> buildMcpTypeData(final CsvFile csvFile, final Map<ClassSrgData, Set<E>> mcpMappings, final Map<String, Map<String, String>> runtimeMappings, NamingFunction<E> mcpNameFunction) {
        final Map<String, Map<String, McpTypeData>> output = Maps.newHashMap();
        // parse over all classes
        mcpMappings.entrySet().forEach(parentClassEntry -> {
            final Map<String, McpTypeData> typeDataMap = Maps.newHashMap();
            // lookup the class in the runtime type map
            final Map<String, String> runtimeClass = runtimeMappings.get(parentClassEntry.getKey().getFullyQualifiedSrgName());
            if(!Objects.isNull(runtimeClass)) {
                // parse over all the methods inside the class
                parentClassEntry.getValue().forEach(data -> {
                    String srgName = data.getSrgName();
                    String obfName = runtimeClass.get(srgName);
                    // get the mcp name (if it exists)
                    CsvData csvData = csvFile.getCsvDataForKey(srgName);
                    String mcpName = !Objects.isNull(csvData) ? csvData.getMcpName() : null;
                    McpTypeData typeData = new McpTypeData(mcpName, srgName, obfName);
                    // add srg to type data conversion
                    typeDataMap.put(srgName, typeData);
                    // add mcp name to type data (if the mcp name exists)
                    if(!Strings.isNullOrEmpty(mcpName)) typeDataMap.put(mcpNameFunction.apply(csvData, data), typeData);
                });
            }
            // class = {mcpTypeName=typeData}
            output.put(parentClassEntry.getKey().getFullyQualifiedSrgName(), Collections.unmodifiableMap(typeDataMap));
        });
        return Collections.unmodifiableMap(output);
    }

    public static class McpTypeData {
        private final String mcpName;
        private final String srgName;
        private final String obfName;

        public McpTypeData(String mcpName, String srgName, String obfName) {
            this.mcpName = mcpName;
            this.srgName = srgName;
            this.obfName = obfName;
        }

        public String getMcpName() {
            return mcpName;
        }

        public String getSrgName() {
            return srgName;
        }

        public String getObfName() {
            return obfName;
        }
    }

    private interface NamingFunction<E extends MemberSrgData> {
        String apply(CsvData csvData, E data);
    }
}
