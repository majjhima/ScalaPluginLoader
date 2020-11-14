package xyz.janboerman.scalaloader.configurationserializable.transform;

import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import xyz.janboerman.scalaloader.bytecode.FieldDeclaration;
import xyz.janboerman.scalaloader.bytecode.LocalVariableDefinition;
import xyz.janboerman.scalaloader.bytecode.MethodHeader;
import xyz.janboerman.scalaloader.configurationserializable.DeserializationMethod;
import xyz.janboerman.scalaloader.configurationserializable.InjectionPoint;
import xyz.janboerman.scalaloader.configurationserializable.Scan;
import xyz.janboerman.scalaloader.configurationserializable.Scan.ExcludeProperty;
import xyz.janboerman.scalaloader.configurationserializable.Scan.IncludeProperty;
import xyz.janboerman.scalaloader.util.Pair;

import static xyz.janboerman.scalaloader.configurationserializable.DeserializationMethod.*;
import static xyz.janboerman.scalaloader.configurationserializable.Scan.Type.*;
import static xyz.janboerman.scalaloader.configurationserializable.transform.Conversions.*;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.objectweb.asm.Opcodes.*;

import static xyz.janboerman.scalaloader.configurationserializable.transform.ConfigurationSerializableTransformations.*;

class SerializableTransformer extends ClassVisitor {

    private final LocalScanResult result;

    private String className;       //uses slashes, not dots:                   foo/bar/SomeClass
    private String classDescriptor; //uses the norminal descriptor notation:    Lfoo/bar/SomeClass;
    private String superType;       //uses slashes:                             java/lang/Object
    private String classSignature;  //includes generics                         Lfoo/bar/Seq<Lfoo/bar/Quz;>;
    private boolean classIsInterface;

    private boolean alreadyHasSerializeMethod;
    private boolean alreadyHasDeserializeMethod;
    private boolean alreadyHasValueOfMethod;
    private boolean alreadyHasDeserializationContructor;
    private boolean alreadyHasNullaryConstructor;
    private boolean alreadyHasClassInitializer;
    private boolean alreadyHasModule$;

    //TODO shouldn't I just keep the parameter names list as a field of MethodHeader?
    private final Map<MethodHeader, List<String> /*parameter names (may be empty!)*/> applyHeaders = new HashMap<>(0);
    private final List<MethodHeader> unapplyHeaders = new ArrayList<>(0);

    private String serializableAs;  //default value is the empty string which is checked in its own special way
    private DeserializationMethod constructUsing = DESERIALIZE;         //same as the default in the annotation
    private InjectionPoint registerAt = InjectionPoint.PLUGIN_ONENABLE; //same as the default in the annotation
    private Scan.Type scanType = Scan.Type.FIELDS;                      //same as the default in the annotation

    private final Map<String /*property*/, MethodHeader> propertyGetters = new LinkedHashMap<>();       //TODO check whether the result to a put call is null
    private final Map<String /*property*/, MethodHeader> propertySetters = new LinkedHashMap<>();       //TODO if it is not, throw a ConfigurationSerializableError
    private final Map<String /*property*/, FieldDeclaration> propertyFields = new LinkedHashMap<>();    //TODO idem!

    SerializableTransformer(ClassVisitor classVisitor, LocalScanResult scanResult) {
        super(ASM_API, classVisitor);
        this.result = scanResult;
    }

    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        if (result.annotatedByConfigurationSerializable) {
            this.className = name;
            this.classDescriptor = 'L' + name + ';';
            this.classSignature = signature;
            this.superType = superName;
            this.classIsInterface = (access & ACC_INTERFACE) == ACC_INTERFACE;

            //make the class public
            access = (access | ACC_PUBLIC) & ~(ACC_PRIVATE | ACC_PROTECTED);

            if (!result.implementsConfigurationSerializable) {
                String[] newInterfaces = new String[interfaces.length + 1];
                System.arraycopy(interfaces, 0, newInterfaces, 0, interfaces.length);
                newInterfaces[interfaces.length] = BUKKIT_CONFIGURATIONSERIALIZABLE_NAME;
                interfaces = newInterfaces;
            }
        }

        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        AnnotationVisitor superVisitor = super.visitAnnotation(descriptor, visible);

        if (SCALALOADER_CONFIGURATIONSERIALIZABLE_DESCRIPTOR.equals(descriptor)) {

            return new AnnotationVisitor(ASM_API, superVisitor) {
                boolean setAlias = false;

                @Override
                public void visit(String name, Object value) {
                    if (AS_NAME.equals(name)) {
                        serializableAs = (String) value;
                        setAlias = true;
                    }
                    super.visit(name, value);
                }

                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    if (CONSTRUCTUSING_NAME.equals(name) && SCALALOADER_DESERIALIZATIONMETHOD_DESCRIPTOR.equals(descriptor)) {
                        constructUsing = DeserializationMethod.valueOf(value);
                    } else if (REGISTERAT_NAME.equals(name) && SCALALOADER_INJECTIONPOINT_DESCRIPTOR.equals(descriptor)) {
                        registerAt = InjectionPoint.valueOf(value);
                    }
                    super.visit(name, value);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                    AnnotationVisitor superVisitor = super.visitAnnotation(name, descriptor);
                    if (SCAN_NAME.equals(name) && SCALALOADER_SCAN_DESCRIPTOR.equals(descriptor)) {
                        return new AnnotationVisitor(ASM_API, superVisitor) {
                            @Override
                            public void visitEnum(String name, String descriptor, String value) {
                                if ("value".equals(name) && SCALALAODER_SCANTYPE_DESCRIPTOR.equals(descriptor)) {
                                    scanType = Scan.Type.valueOf(value);
                                }

                                super.visitEnum(name, descriptor, value);
                            }
                        };
                    }
                    return superVisitor;
                }

                @Override
                public void visitEnd() {
                    super.visitEnd();
                    if (setAlias && !result.annotatedBySerializableAs) { //generate @SerialiableAs(<the alias>) if it wasn't present
                        AnnotationVisitor av = SerializableTransformer.this.visitAnnotation(BUKKIT_SERIALIZABLEAS_DESCRIPTOR, true);
                        av.visit("value", serializableAs);
                        av.visitEnd();
                    }
                }
            };
        }

        else if (BUKKIT_SERIALIZABLEAS_DESCRIPTOR.equals(descriptor)) {
            return new AnnotationVisitor(ASM_API, superVisitor) {
                @Override
                public void visit(String name, Object value) {
                    if ("value".equals(name)) {
                        serializableAs = (String) value;
                    }
                    super.visit(name, value);
                }
            };
        }

        return superVisitor;
    }

    @Override
    public FieldVisitor visitField(int access, String fieldName, String fieldDescriptor, String fieldSignature, Object value) {
        if ("MODULE$".equals(fieldName) && (access & ACC_STATIC) == ACC_STATIC && fieldDescriptor.equals(classDescriptor)) {
            alreadyHasModule$ = true;
        }

        if (result.annotatedByConfigurationSerializable && (access & ACC_STATIC) == 0 && (access & ACC_TRANSIENT) == 0) {
            //TODO only do this for ScanTypes FIELDS and RECORD.
            return new FieldVisitor(ASM_API, super.visitField(access, fieldName, fieldDescriptor, fieldSignature, value)) {
                String property = fieldName;
                boolean include;
                boolean exclude;

                @Override
                public AnnotationVisitor visitAnnotation(String annDescriptor, boolean visible) {
                    AnnotationVisitor superVisitor = super.visitAnnotation(annDescriptor, visible);

                    if (SCALALOADER_PROPERTYINCLUDE_DESCRIPTOR.equals(annDescriptor)) {
                        include = true;
                        return new AnnotationVisitor(ASM_API, superVisitor) {
                            @Override
                            public void visit(String name, Object value) {
                                if ("value".equals(name)) {
                                    property = (String) value;
                                }
                                super.visit(name, value);
                            }
                        };
                    }

                    else if (SCALALOADER_PROPERTYEXCLUDE_DESCRIPTOR.equals(annDescriptor)) {
                        exclude = true;
                        property = null;
                        return superVisitor;
                    }

                    else {
                        return superVisitor;
                    }
                }

                @Override
                public void visitEnd() {
                    if (include && exclude) {
                        throw new ConfigurationSerializableError("Can't annotate field " + fieldName + " with both "
                            + "@" + IncludeProperty.class.getSimpleName() + " and "
                            + "@" + ExcludeProperty.class.getSimpleName() + ", please remove one of the two!");
                    }
                    if (property != null) {
                        propertyFields.put(property, new FieldDeclaration(access, fieldName, fieldDescriptor, fieldSignature));
                    }
                    super.visitEnd();
                }
            };
        }

        else {
            return super.visitField(access, fieldName, fieldDescriptor, fieldSignature, value);
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor, String methodSignature, String[] exceptions) {
        MethodVisitor superVisitor = super.visitMethod(access, methodName, methodDescriptor, methodSignature, exceptions);
        if (result.annotatedByConfigurationSerializable) {
            boolean isStatic = (access & ACC_STATIC) == ACC_STATIC;

            if (!isStatic && SERIALIZE_NAME.equals(methodName) && SERIALIZE_DESCRIPTOR.equals(methodDescriptor)) {
                alreadyHasSerializeMethod = true;
                //make serialize() public in case it wasn't.
                access = (access | ACC_PUBLIC) & ~(ACC_PRIVATE | ACC_PROTECTED);
            }

            else if (!isStatic && CONSTRUCTOR_NAME.equals(methodName) && "()V".equals(methodDescriptor)) {
                alreadyHasNullaryConstructor = true;
            }

            else if (!isStatic && CONSTRUCTOR_NAME.equals(methodName) && DESERIALIZATION_CONSTRUCTOR_DESCRIPTOR.equals(methodDescriptor)) {
                alreadyHasDeserializationContructor = true;
                //make Foo(Map<String, Object>) public in case it wasn't.
                access = (access | ACC_PUBLIC) & ~(ACC_PRIVATE | ACC_PROTECTED);
            }

            else if (isStatic && DESERIALIZE_NAME.equals(methodName) && deserializationDescriptor(classDescriptor).equals(methodDescriptor)) {
                alreadyHasDeserializeMethod = true;
                //make deserialize(Map<String, Object>) public in case it wasn't.
                access = (access | ACC_PUBLIC) & ~(ACC_PRIVATE | ACC_PROTECTED);
            }

            else if (isStatic && VALUEOF_NAME.equals(methodName) && deserializationDescriptor(classDescriptor).equals(methodDescriptor)) {
                alreadyHasValueOfMethod = true;
                //make valueOf(Map<String, Object>) public in case it wasn't.
                access = (access | ACC_PUBLIC) & ~(ACC_PRIVATE | ACC_PROTECTED);
            }

            else if (isStatic && CLASS_INIT_NAME.equals(methodName) && "()V".equals(methodDescriptor)) {
                alreadyHasClassInitializer = true;

                if (registerAt == InjectionPoint.CLASS_INITIALIZER) {

                    return new MethodVisitor(ASM_API, superVisitor) {
                        @Override
                        public void visitCode() {
                            //call registerWithConfigurationSerialization$()
                            visitMethodInsn(INVOKESTATIC, className, REGISTER_NAME, REGISTER_DESCRIPTOR, classIsInterface);
                            super.visitCode();
                        }
                    };
                }
            }

            else if (isStatic && "apply".equals(methodName)) {
                MethodHeader mh = new MethodHeader(access, methodName, methodDescriptor, methodSignature, exceptions);
                List<String> paramNames = new ArrayList<>(2);
                applyHeaders.put(mh, paramNames);
                return new MethodVisitor(ASM_API, superVisitor) {
                    @Override
                    public void visitParameter(String name, int access) {
                        paramNames.add(name);
                    }
                };
            }

            else if (isStatic && "unapply".equals(methodName)) {
                unapplyHeaders.add(new MethodHeader(access, methodName, methodDescriptor, methodSignature, exceptions));
            }

            final int methodAccess = access;
            Type methodType = Type.getMethodType(methodDescriptor);
            Type[] argumentTypes = methodType.getArgumentTypes();
            Type returnType = methodType.getReturnType();

            boolean isGetter = !isStatic && argumentTypes.length == 0 && !returnType.equals(Type.VOID_TYPE);
            boolean isSetter = !isStatic && argumentTypes.length == 1 && (returnType.equals(Type.VOID_TYPE) || returnType.equals(Type.getType(classDescriptor)));

            if (isGetter || isSetter) {
                return new MethodVisitor(ASM_API, superVisitor) {

                    @Override
                    public AnnotationVisitor visitAnnotation(String annDescriptor, boolean visible) {
                        if (SCALALOADER_PROPERTYINCLUDE_DESCRIPTOR.equals(annDescriptor)) {
                            return new AnnotationVisitor(ASM_API, super.visitAnnotation(annDescriptor, visible)) {
                                String propertyKey = methodName;
                                boolean adaptBeanOrScalaConventions = true;

                                @Override
                                public void visit(String name, Object value) {
                                    if ("value".equals(name)) {
                                        propertyKey = (String) value;
                                        adaptBeanOrScalaConventions = false;   //a key was set explicitly, don't adapt.
                                    } else if (ADAPT_NAME.equals(name) && value.equals(false)) {
                                        adaptBeanOrScalaConventions = false;   //obviously, don't adapt.
                                    }

                                    super.visit(name, value);
                                }

                                @Override
                                public void visitEnd() {
                                    if (isSetter) {
                                        if (adaptBeanOrScalaConventions) {
                                            if ((propertyKey.startsWith("set") && propertyKey.length() > 3)) {
                                                propertyKey = Character.toLowerCase(propertyKey.charAt(3)) + propertyKey.substring(4);
                                            } else if (propertyKey.endsWith("_$eq")) {
                                                propertyKey = propertyKey.substring(0, propertyKey.length() - 4);
                                            }
                                        }

                                        propertySetters.put(propertyKey, new MethodHeader(methodAccess, methodName, methodDescriptor, methodSignature, exceptions));
                                    } else if (isGetter) {
                                        if (adaptBeanOrScalaConventions) {
                                            if ((propertyKey.startsWith("get") && propertyKey.length() > 3)) {
                                                propertyKey = Character.toLowerCase(propertyKey.charAt(3)) + propertyKey.substring(4);
                                            } else if (propertyKey.startsWith("is") && propertyKey.length() > 2) {
                                                propertyKey = Character.toLowerCase(propertyKey.charAt(2)) + propertyKey.substring(3);
                                            }
                                        }

                                        propertyGetters.put(propertyKey, new MethodHeader(methodAccess, methodName, methodDescriptor, methodSignature, exceptions));
                                    }
                                    super.visitEnd();
                                }
                            };
                        } else {
                            return super.visitAnnotation(annDescriptor, visible);
                        }
                    }
                };
            }   //else: neither a getter nor setter
        }

        //not annotated by ConfigurationSerializable
        return superVisitor;
    }

    @Override
    public void visitEnd() {
        annotatedByConfigurationSerializable:
        if (result.annotatedByConfigurationSerializable) {

            // do some checks!

            // verify that if our scan type is SINGLETON_OBJECT, then there is a MODULE$
            if (scanType == SINGLETON_OBJECT && !alreadyHasModule$)
                break annotatedByConfigurationSerializable;


            boolean hasDeserizalizationMethod = alreadyHasDeserializationContructor || alreadyHasValueOfMethod || alreadyHasDeserializeMethod;

            //verify that every getter has a setter and vice versa - only needed when we need to generate both the serializer and deserializer methods
            if (!alreadyHasSerializeMethod && !hasDeserizalizationMethod && scanType == GETTER_SETTER_METHODS) {
                if (!propertyGetters.keySet().equals(propertySetters.keySet())) {
                    Set<String> getterProperties = new HashSet<>(propertyGetters.keySet());
                    Set<String> setterProperties = new HashSet<>(propertySetters.keySet());
                    for (String getter : getterProperties)
                        if (!setterProperties.remove(getter))
                            throw new ConfigurationSerializableError("Missing setter method for property: " + getter);
                    for (String setter : setterProperties)
                        if (!getterProperties.remove(setter))
                            throw new ConfigurationSerializableError("Missing getter method for property: " + setter);
                } else {
                    for (Entry<String, MethodHeader> entry : propertyGetters.entrySet()) {
                        String property = entry.getKey();
                        MethodHeader getter = entry.getValue();
                        MethodHeader setter = propertySetters.get(property);
                        signature: {
                            String getterSignature = getter.getReturnSignature();
                            if (getterSignature == null)
                                break signature;
                            String setterSignature = setter.getParameterSignature(0);
                            if (setterSignature == null)
                                break signature;
                            if (!getterSignature.equals(setterSignature))
                                throw new ConfigurationSerializableError("Incompatible getter/setter combination for property: " + property);
                        }
                        descriptor: {
                            String getterDescriptor = getter.getReturnDescriptor();
                            String setterDescriptor = setter.getParameterDescriptor(0);
                            if (!getterDescriptor.equals(setterDescriptor))
                                throw new ConfigurationSerializableError("Incompatible getter/setter combination for property: " + property);
                        }
                    }
                }
            }

            MethodHeader unapplyHeader = null;
            int unapplyParamCount = 0;
            MethodHeader applyHeader = null;
            List<String> applyParamNames = null;

            if (scanType == CASE_CLASS) {
                Optional<Pair<MethodHeader, Integer>> unapply = unapplyHeaders.stream()
                        .map(header -> {
                            String returnSignature = header.getReturnSignature();
                            int paramCount;
                            if (returnSignature == null) {
                                paramCount = 0;
                            } else {
                                UnapplyParamCounter counter = new UnapplyParamCounter();
                                SignatureReader reader = new SignatureReader(returnSignature);
                                reader.accept(counter);
                                paramCount = counter.getParamCount();
                            }

                            return new Pair<>(header, paramCount);
                        })
                        .max(Comparator.comparingInt(Pair::getSecond));

                if (!unapply.isPresent())
                    throw new ConfigurationSerializableError("using serialization method CASE_CLASS but unapply method does not exist");

                Pair<MethodHeader, Integer> unApp = unapply.get();
                unapplyHeader = unApp.getFirst();
                unapplyParamCount = unApp.getSecond();

                boolean matchingApply = false;
                for (Entry<MethodHeader, List<String>> entry : applyHeaders.entrySet()) {
                    applyHeader = entry.getKey();
                    applyParamNames = entry.getValue();
                    if (Type.getMethodType(applyHeader.descriptor).getArgumentTypes().length == unapplyParamCount) {
                        matchingApply = true;
                        if (applyParamNames.size() != unapplyParamCount) {
                            //class was not compiled with parameter names in the bytecode, generate the names here.
                            applyParamNames = IntStream.range(0, unapplyParamCount)
                                    .mapToObj(i -> "property" + i)
                                    .collect(Collectors.toList());
                        }
                        break;
                    }
                }

                if (!matchingApply)
                    throw new ConfigurationSerializableError("using serialization method CASE_CLASS but there is no matching apply method for " + unapplyHeader.name);
                    //ideally, we would want to actually verify the parameter types too. this was only verifying the number of parameters.
                    //sadly, scalac does output signatures like Option<Tuple2<Object,Object>> so that's not really possible without reading the scala signature!
            }


            // finally we get to the code generation part!
            // first up: the serialize() method!

            if (!alreadyHasSerializeMethod) {
                //generate serialize method.

                //start by creating a new hashmap and storing it in the local variable table at index 1.
                MethodVisitor methodVisitor = visitMethod(ACC_PUBLIC, SERIALIZE_NAME, SERIALIZE_DESCRIPTOR, SERIALIZE_SIGNATURE, null);
                methodVisitor.visitCode();
                final Label label0 = new Label();
                methodVisitor.visitLabel(label0);
                methodVisitor.visitTypeInsn(NEW, HASHMAP_NAME);
                methodVisitor.visitInsn(DUP);
                methodVisitor.visitMethodInsn(INVOKESPECIAL, HASHMAP_NAME, CONSTRUCTOR_NAME, "()V", false);
                methodVisitor.visitVarInsn(ASTORE, 1);
                final Label label1 = new Label();
                methodVisitor.visitLabel(label1);

                int maxStack = 2;
                int maxLocal = 2;

                List<LocalVariableDefinition> extraLocalVariables = new ArrayList<>(0);

                switch (scanType) {
                    case FIELDS:
                        int maxStackLocal = 0;  //how many extra stack frames are needed by toSerializedType
                        Label lastLabel = label1;

                        for (Entry<String, FieldDeclaration> entry : propertyFields.entrySet()) {
                            String property = entry.getKey();
                            FieldDeclaration field = entry.getValue();

                            methodVisitor.visitVarInsn(ALOAD, 1);
                            methodVisitor.visitLdcInsn(property);
                            methodVisitor.visitVarInsn(ALOAD, 0);
                            methodVisitor.visitFieldInsn(GETFIELD, className, field.name, field.descriptor);
                            final Label newLabel = new Label();
                            final StackLocal stackLocal = toSerializedType(methodVisitor, field.descriptor, field.signature, maxLocal, lastLabel, newLabel, extraLocalVariables);
                            methodVisitor.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, MAP_PUT_NAME, MAP_PUT_DESCRIPTOR, true);
                            methodVisitor.visitInsn(POP); // discard the return value of Map.put
                            methodVisitor.visitLabel(newLabel);

                            lastLabel = newLabel;
                            maxStackLocal += Math.max(maxStackLocal, 3 + stackLocal.increasedMaxStack);
                            maxLocal += stackLocal.usedLocals;
                        }

                        maxStack += maxStackLocal;

                        //return the map
                        methodVisitor.visitVarInsn(ALOAD, 1);
                        methodVisitor.visitInsn(ARETURN);

                        break;
                    case GETTER_SETTER_METHODS:
                        /*int*/ maxStackLocal = 0; //how many extra stack frames are needed by toSerializedType
                        /*Label*/ lastLabel = label1;

                        for (Entry<String, MethodHeader> entry : propertyGetters.entrySet()) {
                            String property = entry.getKey();
                            MethodHeader methodHeader = entry.getValue();

                            methodVisitor.visitVarInsn(ALOAD, 1);
                            methodVisitor.visitLdcInsn(property);
                            methodVisitor.visitVarInsn(ALOAD, 0);
                            final int INVOKE = (methodHeader.access & ACC_PRIVATE) == ACC_PRIVATE ? INVOKESPECIAL : INVOKEVIRTUAL;
                            methodVisitor.visitMethodInsn(INVOKE, className, methodHeader.name, methodHeader.descriptor, false);
                            final Label newLabel = new Label();
                            final StackLocal stackLocal = toSerializedType(methodVisitor, methodHeader.getReturnDescriptor(), methodHeader.getReturnSignature(), maxLocal, lastLabel, newLabel, extraLocalVariables);
                            methodVisitor.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, MAP_PUT_NAME, MAP_PUT_DESCRIPTOR, true);
                            methodVisitor.visitInsn(POP); // discard the return value of Map.put
                            methodVisitor.visitLabel(newLabel);

                            lastLabel = newLabel;
                            maxStackLocal += Math.max(maxStackLocal, 3 + stackLocal.increasedMaxStack);
                            maxLocal += stackLocal.usedLocals;
                        }

                        maxStack += maxStackLocal;

                        //return the map
                        methodVisitor.visitVarInsn(ALOAD, 1);
                        methodVisitor.visitInsn(ARETURN);

                        break;
                    case RECORD:
                        //use all record component accessors (find them by scanning the fields!)

                        /*int*/ maxStackLocal = 0; //how many extra stack frames are needed by toSerializedType
                        /*Label*/ lastLabel = label1;

                        for (Entry<String, FieldDeclaration> entry : propertyFields.entrySet()) {
                            String property = entry.getKey();
                            FieldDeclaration fieldDeclaration = entry.getValue();

                            methodVisitor.visitVarInsn(ALOAD, 1);   //load map onto the stack
                            methodVisitor.visitLdcInsn(property);       //load property key onto the stack
                            methodVisitor.visitVarInsn(ALOAD, 0);   //load 'this' onto the stack
                            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, className, property, "()" + fieldDeclaration.descriptor, false);
                            final Label newLabel = new Label();
                            final StackLocal stackLocal = toSerializedType(methodVisitor, fieldDeclaration.descriptor, fieldDeclaration.signature, maxLocal, lastLabel, newLabel, extraLocalVariables);
                            methodVisitor.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, MAP_PUT_NAME, MAP_PUT_DESCRIPTOR, true);
                            methodVisitor.visitInsn(POP);
                            methodVisitor.visitLabel(newLabel);

                            lastLabel = newLabel;
                            maxStackLocal = Math.max(maxStackLocal, 3 + stackLocal.increasedMaxStack);
                            maxLocal += stackLocal.usedLocals;
                        }

                        maxStack += maxStackLocal;

                        //return the map
                        methodVisitor.visitVarInsn(ALOAD, 1);
                        methodVisitor.visitInsn(ARETURN);

                        break;
                    case CASE_CLASS:
                        //call unapply. (the one with the most type parameters)
                        //to get the property names, the parameter names from the matching apply method are used.
                        assert applyHeader != null : "applyHeader is null when trying to generate code for serialize() method for ScanType CASE_CLASS";
                        assert unapplyHeader != null : "unapplyHeader is null when trying to generate code for seralize() method for ScanType CASE_CLASS";

                        if (unapplyParamCount == 0) {
                            //just continue to the end, return the map as-is.

                            //call unapply(this)
                            methodVisitor.visitVarInsn(ALOAD, 0);
                            methodVisitor.visitMethodInsn(INVOKESTATIC, className, "unapply", unapplyHeader.descriptor, classIsInterface);
                            maxStack = Math.max(maxStack, 1);
                            //we now have just a boolean on top of the operand stack

                            final Label failLabel = new Label();

                            methodVisitor.visitJumpInsn(IFEQ, failLabel);

                            //unapply successful: return the empty map
                            methodVisitor.visitVarInsn(ALOAD, 1);
                            methodVisitor.visitInsn(ARETURN);

                            //unapply unsuccessful: return null
                            methodVisitor.visitLabel(failLabel);
                            methodVisitor.visitFrame(F_APPEND, 1, new Object[] {MAP_NAME}, 0, null);
                            methodVisitor.visitInsn(ACONST_NULL);
                            methodVisitor.visitInsn(ARETURN);

                            maxStack = Math.max(maxStack, 1);

                        } else if (unapplyParamCount == 1) {
                            //put the thing into the map

                            //call unapply(this)
                            methodVisitor.visitVarInsn(ALOAD, 0);
                            methodVisitor.visitMethodInsn(INVOKESTATIC, className, "unapply", unapplyHeader.descriptor, classIsInterface);
                            maxStack = Math.max(1, maxStack);

                            final Label endTestVarLabel = new Label();
                            final int testIndex = maxLocal++;
                            extraLocalVariables.add(new LocalVariableDefinition("test", OPTION_DESCRIPTOR, "Lscala/Option<Ljava/lang/Object;>;", label1, endTestVarLabel, testIndex));
                            methodVisitor.visitVarInsn(ASTORE, testIndex);

                            final Label beforeIsDefinedLabel = new Label();
                            //call (test: Option).isDefined
                            methodVisitor.visitLabel(beforeIsDefinedLabel);
                            methodVisitor.visitVarInsn(ALOAD, testIndex);
                            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, OPTION_NAME, "isDefined", "()Z", false);
                            maxStack = Math.max(maxStack, 1);

                            final Label definedFalseJumpTarget = new Label();
                            methodVisitor.visitJumpInsn(IFEQ, definedFalseJumpTarget);   //assume this consumes the boolean

                            //defined - extract member from the option, put it in the map, return the map
                            final Label definedTrueLabel = new Label();
                            methodVisitor.visitLabel(definedTrueLabel);

                            String propertyName = applyParamNames.get(0);
                            String paramSignature = applyHeader.getParameterSignature(0);
                            String paramDescriptor = applyHeader.getParameterDescriptor(0);
                            String paramType = Type.getType(paramDescriptor).getInternalName();

                            methodVisitor.visitVarInsn(ALOAD, 1);   //load map onto the stack
                            methodVisitor.visitLdcInsn(propertyName);       //the name of the argument
                            methodVisitor.visitVarInsn(ALOAD, testIndex);           // load option onto the stack
                            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, OPTION_NAME, "get", "()Ljava/lang/Object;", false);
                            //conversion from object to serialized type
                            methodVisitor.visitTypeInsn(CHECKCAST, boxedType(paramType)); //cast from java.lang.Object to the type of live object.
                            StackLocal sl = toSerializedType(methodVisitor, boxedDescriptor(paramDescriptor), paramSignature, maxLocal, definedTrueLabel, endTestVarLabel, extraLocalVariables);
                            //put the value in the map
                            methodVisitor.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, MAP_PUT_NAME, MAP_PUT_DESCRIPTOR, true);
                            methodVisitor.visitInsn(POP);   //discard old value of the map

                            //end of branch
                            methodVisitor.visitLabel(endTestVarLabel);

                            //return the map
                            methodVisitor.visitVarInsn(ALOAD, 1);
                            methodVisitor.visitInsn(ARETURN);

                            maxStack = Math.max(maxStack, 3 + sl.increasedMaxStack);
                            maxLocal += sl.usedLocals;

                            //not defined - return null;
                            methodVisitor.visitLabel(definedFalseJumpTarget);
                            //methodVisitor.visitFrame(F_FULL, 3, new Object[]{className, MAP_NAME, "scala/Option"}, 0, new Object[0]); //3 local variables, empty stack
                            methodVisitor.visitFrame(F_APPEND, 2, new Object[]{MAP_NAME, OPTION_NAME}, 0, null);
                            methodVisitor.visitInsn(ACONST_NULL);
                            methodVisitor.visitInsn(ARETURN);
                            maxStack = Math.max(maxStack, 1);

                        } else /*unapplyHeader.getReturnSignature() equals Option<Tuple2>, Option<Tuple3>, ...*/ {
                            //put the many things into the map

                            //call unapply(this)
                            methodVisitor.visitVarInsn(ALOAD, 0);
                            methodVisitor.visitMethodInsn(INVOKESTATIC, className, "unapply", unapplyHeader.descriptor, classIsInterface);
                            maxStack = Math.max(1, maxStack);

                            final Label endTestVarLabel = new Label();
                            final int testIndex = maxLocal++;
                            extraLocalVariables.add(new LocalVariableDefinition("test", OPTION_DESCRIPTOR, "Lscala/Option<Ljava/lang/Object;>;", label1, endTestVarLabel, testIndex));
                            methodVisitor.visitVarInsn(ASTORE, testIndex);

                            final Label beforeIsDefinedLabel = new Label();
                            //call (test: Option).isDefined
                            methodVisitor.visitLabel(beforeIsDefinedLabel);
                            methodVisitor.visitVarInsn(ALOAD, testIndex);
                            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, OPTION_NAME, "isDefined", "()Z", false);
                            maxStack = Math.max(maxStack, 1);

                            final Label definedFalseJumpTarget = new Label();
                            methodVisitor.visitJumpInsn(IFEQ, definedFalseJumpTarget);

                            //defined - extract member from the option and return
                            final Label definedTrueLabel = new Label();
                            methodVisitor.visitLabel(definedTrueLabel);

                            final String tupleName = "scala/Tuple" + unapplyParamCount;
                            final int tupleIndex = maxLocal++;

                            //define and store tuple local variable
                            extraLocalVariables.add(new LocalVariableDefinition("tup", 'L' + tupleName + ';', null /*TODO?*/, definedTrueLabel, endTestVarLabel, tupleIndex));
                            methodVisitor.visitVarInsn(ALOAD, testIndex);
                            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, OPTION_NAME, "get", "()Ljava/lang/Object;", false);
                            methodVisitor.visitTypeInsn(CHECKCAST, tupleName);
                            methodVisitor.visitVarInsn(ASTORE, tupleIndex);
                            maxStack = Math.max(maxStack, 1);

                            int extraLocals = 0, extraStack = 0;
                            for (int paramIndex = 0; paramIndex < unapplyParamCount; paramIndex++) {

                                String propertyName = applyParamNames.get(paramIndex);

                                String paramSignature = applyHeader.getParameterSignature(paramIndex);
                                String paramDescriptor = applyHeader.getParameterDescriptor(paramIndex);
                                String paramType = Type.getType(paramDescriptor).getInternalName();
                                String indexMethod = "_" + (paramIndex + 1);

                                methodVisitor.visitVarInsn(ALOAD, 1);   //load map onto the stack
                                methodVisitor.visitLdcInsn(propertyName);    //the name of the argument
                                methodVisitor.visitVarInsn(ALOAD, 3);   // load tuple onto the stack
                                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, tupleName, indexMethod, "()Ljava/lang/Object;", false);

                                //conversion from object to serialized type
                                methodVisitor.visitTypeInsn(CHECKCAST, boxedType(paramType));
                                StackLocal sl = toSerializedType(methodVisitor, boxedDescriptor(paramDescriptor), paramSignature, maxLocal, definedTrueLabel, endTestVarLabel, extraLocalVariables);
                                //put the value in the map
                                methodVisitor.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, MAP_PUT_NAME, MAP_PUT_DESCRIPTOR, true);
                                methodVisitor.visitInsn(POP);   //discard old value of the map

                                extraStack = Math.max(extraStack, 3 + sl.increasedMaxStack);
                                extraLocals += sl.usedLocals;
                            }

                            //end of branch - return the map
                            methodVisitor.visitLabel(endTestVarLabel);
                            methodVisitor.visitVarInsn(ALOAD, 1);
                            methodVisitor.visitInsn(ARETURN);

                            maxStack += extraStack;
                            maxLocal += extraLocals;

                            methodVisitor.visitLabel(definedFalseJumpTarget);
                            //not defined - return null;
                            methodVisitor.visitFrame(F_APPEND, 2, new Object[]{MAP_NAME, OPTION_NAME}, 0, null);
                            methodVisitor.visitInsn(ACONST_NULL);
                            methodVisitor.visitInsn(ARETURN);
                            maxStack = Math.max(maxStack, 1);
                        }

                        //no common code anymore for case CASE_CLASS. visitLocalVariable etc is called at the end.

                        break;
                    case ENUM:
                        //we already have a hashmap ready for use! (local variable index 1)

                        methodVisitor.visitVarInsn(ALOAD, 1);   //load map
                        methodVisitor.visitLdcInsn("name");     //load string constant
                        methodVisitor.visitVarInsn(ALOAD, 0);   //load 'this'
                        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, className, "name", "()Ljava/lang/String;", false);
                        methodVisitor.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, MAP_PUT_NAME, MAP_PUT_DESCRIPTOR, true);
                        methodVisitor.visitInsn(POP); //ignore return value of Map.put

                        Label doneLabel = new Label();
                        methodVisitor.visitLabel(doneLabel);

                        //return the map
                        methodVisitor.visitVarInsn(ALOAD, 1);
                        methodVisitor.visitInsn(ARETURN);

                        maxStack = Math.max(3, maxStack);

                        break;

                    case SINGLETON_OBJECT:
                        assert alreadyHasModule$ : "scanType SINGELTON_OBJECT without a MODULE$ static field";

                        //literally zero extra code is needed to put values into the map - we just need to return it!
                        methodVisitor.visitVarInsn(ALOAD, 1);
                        methodVisitor.visitInsn(ARETURN);

                        break;

                    //case CONSTANTS
                        //TODO ideally I'd just encode a 'switch' on strings in bytecode
                        //break;
                }

                final Label lastLabel = new Label();
                methodVisitor.visitLabel(lastLabel);
                methodVisitor.visitLocalVariable("this", classDescriptor, classSignature, label0, lastLabel, 0);
                methodVisitor.visitLocalVariable("map", MAP_DESCRIPTOR, MAP_SIGNATURE, label1, lastLabel, 1);
                for (LocalVariableDefinition local : extraLocalVariables) {
                    methodVisitor.visitLocalVariable(local.name, local.descriptor, local.signature, local.startLabel, local.endLabel, local.tableIndex);
                }
                methodVisitor.visitMaxs(maxStack, maxLocal);
                methodVisitor.visitEnd();
            }


            // code generation part 2: deserialize!

            if (!hasDeserizalizationMethod) {
                //generate deserialization method

                final List<LocalVariableDefinition> extraLocalVariables = new ArrayList<>(0);

                if (scanType == FIELDS || scanType == GETTER_SETTER_METHODS) {
                    //only generate the nullary constructor if we are using FIELDS or METHODS
                    if (!alreadyHasNullaryConstructor) {
                        //generate private nullary constructor that just calls super();
                        //this might fail at runtime if the superclass has no accessible nullary constructor
                        // TODO test this and catch the exception at classload time so that we can provide a more useful error message!
                        MethodVisitor methodVisitor = this.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
                        methodVisitor.visitCode();
                        Label label0 = new Label();
                        methodVisitor.visitVarInsn(ALOAD, 0);
                        methodVisitor.visitMethodInsn(INVOKESPECIAL, superType, "<init>", "()V", false);
                        Label label1 = new Label();
                        methodVisitor.visitLabel(label1);
                        methodVisitor.visitInsn(RETURN);
                        Label label2 = new Label();
                        methodVisitor.visitLabel(label2);
                        methodVisitor.visitLocalVariable("this", classDescriptor, classSignature, label0, label2, 0);
                        methodVisitor.visitMaxs(1, 1);
                        methodVisitor.visitEnd();
                    }

                    //generate deserialization method depending on constructUsing
                    final MethodVisitor methodVisitor;
                    int maxStack;
                    final boolean thisFirstThenMap;
                    final Label label0 = new Label();
                    switch (constructUsing) {
                        case MAP_CONSTRUCTOR:
                            thisFirstThenMap = true;
                            methodVisitor = this.visitMethod(ACC_PUBLIC, CONSTRUCTOR_NAME, DESERIALIZATION_CONSTRUCTOR_DESCRIPTOR, DESERIALIZATION_CONSTRUCTOR_SIGNATURE, null);
                            methodVisitor.visitCode();
                            //"this" is already in the local variable table. we only need to call the nullary constructor.
                            methodVisitor.visitLabel(label0);
                            methodVisitor.visitVarInsn(ALOAD, 0);
                            methodVisitor.visitMethodInsn(INVOKESPECIAL, className, "<init>", "()V", false);
                            maxStack = 1;
                            break;
                        case DESERIALIZE:
                        case VALUE_OF:
                            thisFirstThenMap = false;
                            methodVisitor = this.visitMethod(ACC_PUBLIC | ACC_STATIC, constructUsing == VALUE_OF ? VALUEOF_NAME : DESERIALIZE_NAME, deserializationDescriptor(classDescriptor), deserializationSignature(classDescriptor), null);
                            methodVisitor.visitCode();
                            //put the value of this() in the local variable table
                            methodVisitor.visitLabel(label0);
                            methodVisitor.visitTypeInsn(NEW, className);
                            methodVisitor.visitInsn(DUP);
                            methodVisitor.visitMethodInsn(INVOKESPECIAL, className, "<init>", "()V", false);
                            methodVisitor.visitVarInsn(ASTORE, 1);
                            maxStack = 2; //because of the dup
                            break;
                        default:
                            throw new RuntimeException("Unreachable, got constructUsing "
                                    + DeserializationMethod.class.getSimpleName() + "."
                                    + constructUsing.name());
                    }

                    final Label label1 = new Label();
                    methodVisitor.visitLabel(label1);
                    Label lastLabel = label1;

                    int localVariableIndex = 2;
                    if (scanType == Scan.Type.FIELDS) {
                        int extraStackUsage = 0;
                        for (Entry<String, FieldDeclaration> entry : propertyFields.entrySet()) {
                            String propertyName = entry.getKey();
                            FieldDeclaration field = entry.getValue();
                            if (thisFirstThenMap) {
                                methodVisitor.visitVarInsn(ALOAD, 0);
                                methodVisitor.visitVarInsn(ALOAD, 1);
                            } else {
                                methodVisitor.visitVarInsn(ALOAD, 1);
                                methodVisitor.visitVarInsn(ALOAD, 0);
                            }
                            final Label newLabel = new Label();
                            methodVisitor.visitLdcInsn(propertyName);
                            methodVisitor.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, MAP_GET_NAME, MAP_GET_DESCRIPTOR, true);
                            final StackLocal stackLocal = toLiveType(methodVisitor, field.descriptor, field.signature, localVariableIndex, lastLabel, newLabel, extraLocalVariables);
                            methodVisitor.visitFieldInsn(PUTFIELD, className, field.name, field.descriptor);
                            methodVisitor.visitLabel(newLabel);

                            extraStackUsage = Math.max(extraStackUsage, stackLocal.increasedMaxStack);
                            localVariableIndex += stackLocal.usedLocals;
                            lastLabel = newLabel;
                        }

                        maxStack += 3;
                        maxStack += extraStackUsage;

                    } else if (scanType == Scan.Type.GETTER_SETTER_METHODS) {
                        int extraStackUsage = 0;
                        for (Entry<String, MethodHeader> entry : propertySetters.entrySet()) {
                            String propertyName = entry.getKey();
                            MethodHeader method = entry.getValue();
                            if (thisFirstThenMap) {
                                methodVisitor.visitVarInsn(ALOAD, 0);
                                methodVisitor.visitVarInsn(ALOAD, 1);
                            } else {
                                methodVisitor.visitVarInsn(ALOAD, 1);
                                methodVisitor.visitVarInsn(ALOAD, 0);
                            }
                            final Label newLabel = new Label();
                            methodVisitor.visitLdcInsn(propertyName);
                            methodVisitor.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, MAP_GET_NAME, MAP_GET_DESCRIPTOR, true);
                            final StackLocal stackLocal = toLiveType(methodVisitor, method.getParameterDescriptor(0), method.getParameterSignature(0), localVariableIndex, lastLabel, newLabel, extraLocalVariables);
                            final int INVOKE = (method.access & ACC_PRIVATE) == ACC_PRIVATE ? INVOKESPECIAL : INVOKEVIRTUAL;
                            methodVisitor.visitMethodInsn(INVOKE, className, method.name, method.descriptor, false);
                            if (!"V".equals(method.getReturnDescriptor())) {
                                methodVisitor.visitInsn(POP);
                            }
                            methodVisitor.visitLabel(newLabel);

                            extraStackUsage = Math.max(extraStackUsage, stackLocal.increasedMaxStack);
                            localVariableIndex += stackLocal.usedLocals;
                            lastLabel = newLabel;
                        }

                        maxStack += 3;
                        maxStack += extraStackUsage;
                    }

                    //return the deserialized instance
                    methodVisitor.visitVarInsn(ALOAD, thisFirstThenMap ? 0 : 1);
                    maxStack += 1;  //probably not necessary but w/e
                    methodVisitor.visitInsn(ARETURN);

                    //lastLabel is already visted at this point
                    if (thisFirstThenMap) {
                        methodVisitor.visitLocalVariable("this", classDescriptor, classSignature, label0, lastLabel, 0);
                        methodVisitor.visitLocalVariable("map", MAP_DESCRIPTOR, MAP_SIGNATURE, label0, lastLabel, 1);
                    } else {
                        methodVisitor.visitLocalVariable("map", MAP_DESCRIPTOR, MAP_SIGNATURE, label0, lastLabel, 0);
                        methodVisitor.visitLocalVariable("result", classDescriptor, classSignature, label1, lastLabel, 1);
                    }
                    for (LocalVariableDefinition extraLocal : extraLocalVariables) {
                        methodVisitor.visitLocalVariable(extraLocal.name, extraLocal.descriptor, extraLocal.signature, extraLocal.startLabel, extraLocal.endLabel, extraLocal.tableIndex);
                    }
                    methodVisitor.visitMaxs(maxStack, localVariableIndex);
                    methodVisitor.visitEnd();
                }


                else if (scanType == RECORD) {
                    //use all-component constructor (which can be determined from the fields)

                    //generate deserialization method depending on constructUsing
                    final MethodVisitor methodVisitor;
                    final Label label0 = new Label();
                    final Label endLabel = new Label();

                    StringJoiner stringJoiner = new StringJoiner("", "(", ")V");
                    for (FieldDeclaration propertyField : propertyFields.values()) {
                        stringJoiner.add(propertyField.descriptor);
                    }
                    String constructorDescriptor = stringJoiner.toString();

                    switch (constructUsing) {
                        case MAP_CONSTRUCTOR:
                            int maxStack = propertyFields.size() + 2, maxLocal = 2;

                            methodVisitor = this.visitMethod(ACC_PUBLIC, CONSTRUCTOR_NAME, DESERIALIZATION_CONSTRUCTOR_DESCRIPTOR, DESERIALIZATION_CONSTRUCTOR_SIGNATURE, null);
                            methodVisitor.visitCode();
                            //"this" is already in the local variable table. we only need to call the constructor.
                            methodVisitor.visitLabel(label0);

                            //load arguments onto the stack
                            //call the other constructor

                            methodVisitor.visitVarInsn(ALOAD, 0); //load 'this' (eventually the <init> method will be called on this instance!)
                            final Label endLoopLabel = new Label();

                            for (Entry<String, FieldDeclaration> entry : propertyFields.entrySet()) {
                                String property = entry.getKey();
                                FieldDeclaration fieldDeclaration = entry.getValue();

                                methodVisitor.visitVarInsn(ALOAD, 1);   //load map
                                methodVisitor.visitLdcInsn(property);       //load string constant onto the stack
                                methodVisitor.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, MAP_GET_NAME, MAP_GET_DESCRIPTOR, true);
                                final StackLocal stackLocal = toLiveType(methodVisitor, fieldDeclaration.descriptor, fieldDeclaration.signature, maxLocal, label0, endLoopLabel, extraLocalVariables);
                                //add an argument on the stack! keep it there!

                                maxStack += stackLocal.increasedMaxStack + 1;
                                maxLocal += stackLocal.usedLocals;
                            }
                            methodVisitor.visitLabel(endLoopLabel);

                            //finally, call the constructor method! that's: this(component1, component2, ...)
                            methodVisitor.visitMethodInsn(INVOKESPECIAL, className, "<init>", constructorDescriptor, false);
                            methodVisitor.visitInsn(RETURN);

                            methodVisitor.visitLabel(endLabel);
                            methodVisitor.visitLocalVariable("this", classDescriptor, classSignature, label0, endLabel, 0);
                            methodVisitor.visitLocalVariable("map", MAP_DESCRIPTOR, MAP_SIGNATURE, label0, endLabel, 1);
                            for (LocalVariableDefinition extraLocal : extraLocalVariables) {
                                methodVisitor.visitLocalVariable(extraLocal.name, extraLocal.descriptor, extraLocal.signature, extraLocal.startLabel, extraLocal.endLabel, extraLocal.tableIndex);
                            }
                            methodVisitor.visitMaxs(maxStack, maxLocal);
                            methodVisitor.visitEnd();

                            break;
                        case DESERIALIZE:
                        case VALUE_OF:
                            maxStack = propertyFields.size() + 2; maxLocal = 1;

                            methodVisitor = this.visitMethod(ACC_PUBLIC | ACC_STATIC, constructUsing == VALUE_OF ? VALUEOF_NAME : DESERIALIZE_NAME, deserializationDescriptor(classDescriptor), deserializationSignature(classDescriptor), null);
                            methodVisitor.visitCode();
                            methodVisitor.visitLabel(label0);

                            //return null if the map argument was null
                            methodVisitor.visitVarInsn(ALOAD, 0);
                            Label notNullLabel = new Label();
                            methodVisitor.visitJumpInsn(IFNONNULL, notNullLabel);
                            methodVisitor.visitInsn(ACONST_NULL);
                            methodVisitor.visitInsn(ARETURN);

                            methodVisitor.visitLabel(notNullLabel);
                            methodVisitor.visitFrame(F_SAME, 0, null, 0, null);

                            methodVisitor.visitTypeInsn(NEW, className);    //will be returned
                            methodVisitor.visitInsn(DUP);                   //will have its <init> method invoked

                            final Label beforeLoopLabel = new Label();
                            final Label afterLoopLabel = new Label();
                            methodVisitor.visitLabel(beforeLoopLabel);
                            for (Entry<String, FieldDeclaration> entry : propertyFields.entrySet()) {
                                String property = entry.getKey();
                                FieldDeclaration fieldDeclaration = entry.getValue();

                                methodVisitor.visitVarInsn(ALOAD, 0);   //load map
                                methodVisitor.visitLdcInsn(property);       //load string constant
                                methodVisitor.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, MAP_GET_NAME, MAP_GET_DESCRIPTOR, true);
                                final StackLocal stackLocal = toLiveType(methodVisitor, fieldDeclaration.descriptor, fieldDeclaration.signature, maxLocal, beforeLoopLabel, afterLoopLabel, extraLocalVariables);

                                maxStack += stackLocal.increasedMaxStack + 1;
                                maxLocal += stackLocal.increasedMaxStack;
                            }
                            methodVisitor.visitLabel(afterLoopLabel);

                            //finally, call the constructor method! that's: this(component1, component2, ...)
                            methodVisitor.visitMethodInsn(INVOKESPECIAL, className, "<init>", constructorDescriptor, false);
                            methodVisitor.visitInsn(ARETURN);

                            methodVisitor.visitLabel(endLabel);
                            methodVisitor.visitLocalVariable("map", MAP_DESCRIPTOR, MAP_SIGNATURE, label0, endLabel, 0);
                            for (LocalVariableDefinition extraLocal : extraLocalVariables) {
                                methodVisitor.visitLocalVariable(extraLocal.name, extraLocal.descriptor, extraLocal.signature, extraLocal.startLabel, extraLocal.endLabel, extraLocal.tableIndex);
                            }
                            methodVisitor.visitMaxs(maxStack, maxLocal);
                            methodVisitor.visitEnd();

                            break;
                        default:
                            throw new RuntimeException("Unreachable, got constructUsing "
                                    + DeserializationMethod.class.getSimpleName() + "."
                                    + constructUsing.name());
                    }
                }

                else if (scanType == CASE_CLASS) {
                    //implements deserialize/valueOf by calling apply
                    //unapply can have the following return types: boolean, Option<java.lang.Object>, Option<Tuple2<Object,Object>>, Option<Tuple3<Object,Object,Object>>, ...

                    switch (constructUsing) {
                        case MAP_CONSTRUCTOR:
                            throw new ConfigurationSerializableError("Can't construct using " + MAP_CONSTRUCTOR.name() + " for scan type " + CASE_CLASS.name() + ".");

                        case VALUE_OF:
                        case DESERIALIZE:
                            String theMethodName = constructUsing == VALUE_OF ? "valueOf" : "deserialize";

                            int maxStack = 0;
                            int maxLocal = 1;   //start with the Map<String, Object>

                            MethodVisitor methodVisitor = visitMethod(ACC_PUBLIC | ACC_STATIC, theMethodName, deserializationDescriptor(classDescriptor), null, null);
                            methodVisitor.visitCode();
                            final Label label0 = new Label();
                            methodVisitor.visitLabel(label0);

                            //check whether the map is null and return null
                            final Label notNullLabel = new Label();
                            methodVisitor.visitVarInsn(ALOAD, 0);   //load the map
                            maxStack = Math.max(1, maxStack);
                            methodVisitor.visitJumpInsn(IFNONNULL, notNullLabel);
                            final Label nullLabel = new Label();
                            methodVisitor.visitLabel(nullLabel);
                            methodVisitor.visitInsn(ACONST_NULL);
                            methodVisitor.visitInsn(ARETURN);

                            //map is not null
                            methodVisitor.visitLabel(notNullLabel);
                            //methodVisitor.visitFrame(F_FULL, 1, new Object[]{MAP_NAME}, 1, new Object[] {MAP_NAME});
                            methodVisitor.visitFrame(F_SAME, 0, null, 0, null);

                            final Label endLabel = new Label();
                            //I don't need to put any reference on the stack first because apply is static! :)

                            for (int i = 0; i < unapplyParamCount; i++) {
                                String property = applyParamNames.get(i);
                                String paramDescriptor = applyHeader.getParameterDescriptor(i);
                                String paramSignature = applyHeader.getParameterSignature(i);

                                methodVisitor.visitVarInsn(ALOAD, 0);   //load 'map'
                                methodVisitor.visitLdcInsn(property);       //load string constant
                                methodVisitor.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, MAP_GET_NAME, MAP_GET_DESCRIPTOR, true);
                                StackLocal sl = toLiveType(methodVisitor, paramDescriptor, paramSignature, maxLocal, label0, endLabel, extraLocalVariables);
                                //converted thing is now on top of the stack!
                                //it will get covered in the next iteration by (first the map and the property name, but then) the next parameter.

                                maxStack += 3; //2 from the ALOAD and LDC + 1 from the extra local variable (this is not completely sound tho)

                                maxLocal += sl.usedLocals;
                                maxStack = Math.max(maxStack, sl.increasedMaxStack);
                            }

                            //all the parameters are now on top of the operand stack
                            //time to call apply!

                            final Label applyLabel = new Label();
                            methodVisitor.visitLabel(applyLabel);
                            methodVisitor.visitMethodInsn(INVOKESTATIC, className, "apply", applyHeader.descriptor, classIsInterface);
                            maxStack = Math.max(1, maxStack);
                            methodVisitor.visitInsn(ARETURN);
                            methodVisitor.visitLabel(endLabel);
                            methodVisitor.visitLocalVariable("map", MAP_DESCRIPTOR, MAP_SIGNATURE, label0, endLabel, 0);
                            for (LocalVariableDefinition extraLocal : extraLocalVariables) {
                                methodVisitor.visitLocalVariable(extraLocal.name, extraLocal.descriptor, extraLocal.signature, extraLocal.startLabel, extraLocal.endLabel, extraLocal.tableIndex);
                            }
                            methodVisitor.visitMaxs(maxStack, maxLocal);
                            methodVisitor.visitEnd();
                            break;
                    }
                }

                else if (scanType == SINGLETON_OBJECT) {
                    //just get the single instance!

                    assert alreadyHasModule$ : "scanType SINGELTON_OBJECT without a MODULE$ static field";

                    //the MODULE$-check is needed because the scala compiler will also generate the annotation @ConfigurationSerializable on the companion class of the singleton object.
                    //if we would also generate this code in the companion class, then the GETSTATIC call will result in a NoSuchFieldError.

                    String deserializationMethodName;
                    switch (constructUsing) {
                        case MAP_CONSTRUCTOR:
                            throw new ConfigurationSerializableError("Can't generate a deserialization constructor for singleton objects, it would violate the object model!");

                        case DESERIALIZE:
                            deserializationMethodName = DESERIALIZE_NAME;
                            break;
                        case VALUE_OF:
                            deserializationMethodName = VALUEOF_NAME;
                            break;
                        default:
                            throw new RuntimeException("Unreachable, got constructUsing "
                                    + DeserializationMethod.class.getSimpleName() + "."
                                    + constructUsing.name());
                    }

                    MethodVisitor methodVisitor = visitMethod(ACC_PUBLIC | ACC_STATIC, deserializationMethodName, deserializationDescriptor(classDescriptor), deserializationSignature(classDescriptor), null);
                    methodVisitor.visitCode();
                    final Label startLabel = new Label();
                    methodVisitor.visitLabel(startLabel);
                    methodVisitor.visitFieldInsn(GETSTATIC, className, "MODULE$", classDescriptor);
                    methodVisitor.visitInsn(ARETURN);
                    final Label endLabel = new Label();
                    methodVisitor.visitLabel(endLabel);
                    methodVisitor.visitLocalVariable("map", MAP_DESCRIPTOR, MAP_SIGNATURE, startLabel, endLabel, 0);
                    methodVisitor.visitMaxs(1, 1);
                    methodVisitor.visitEnd();

                }

                else if (scanType == ENUM) {
                    switch (constructUsing) {
                        case MAP_CONSTRUCTOR:
                            throw new ConfigurationSerializableError("Can't construct using " + MAP_CONSTRUCTOR.name() + " for scan type " + ENUM.name() + ".");

                        case VALUE_OF:
                        case DESERIALIZE:
                            final String theMethodName = constructUsing == VALUE_OF ? "valueOf" : "deserialize";

                            MethodVisitor methodVisitor = visitMethod(ACC_PUBLIC | ACC_STATIC, theMethodName, deserializationDescriptor(classDescriptor), deserializationSignature(classDescriptor), null);
                            methodVisitor.visitCode();
                            Label startLabel = new Label();
                            methodVisitor.visitLabel(startLabel);
                            methodVisitor.visitVarInsn(ALOAD, 0);
                            Label notNullLabel = new Label();
                            methodVisitor.visitJumpInsn(IFNONNULL, notNullLabel);
                            methodVisitor.visitInsn(ACONST_NULL);
                            methodVisitor.visitInsn(ARETURN);

                            methodVisitor.visitLabel(notNullLabel);
                            methodVisitor.visitFrame(F_SAME, 0, null, 0, null);
                            methodVisitor.visitVarInsn(ALOAD, 0);
                            methodVisitor.visitLdcInsn("name");
                            methodVisitor.visitMethodInsn(INVOKEINTERFACE, MAP_NAME, MAP_GET_NAME, MAP_GET_DESCRIPTOR, true);
                            methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/String");
                            methodVisitor.visitMethodInsn(INVOKESTATIC, className, "valueOf", "(Ljava/lang/String;)" + classDescriptor, classIsInterface);
                            methodVisitor.visitInsn(ARETURN);

                            Label endLabel = new Label();
                            methodVisitor.visitLabel(endLabel);
                            methodVisitor.visitLocalVariable("map", MAP_DESCRIPTOR, MAP_SIGNATURE, startLabel, endLabel, 0);
                            methodVisitor.visitMaxs(2, 1);
                            methodVisitor.visitEnd();
                    }
                }

                /* else if (scanType == CONSTANTS) {
                 *     //TODO switch on string constants
                 * }
                 */
            }

            //generate public static void registerWithConfigurationSerializable$() { ConfigurationSerialization.registerClass(MyClass.class [,"myAlias"]?); }



            // code generation part 3:

            // generate a static method on the class that when called will register the class to Bukkit's ConfigurationSerialization system.
            boolean noAlias = serializableAs == null || serializableAs.isEmpty();
            MethodVisitor methodVisitor = this.visitMethod(ACC_PUBLIC | ACC_STATIC, REGISTER_NAME, REGISTER_DESCRIPTOR, null, null);
            methodVisitor.visitCode();
            Label label0 = new Label();
            methodVisitor.visitLabel(label0);
            methodVisitor.visitLdcInsn(Type.getType(classDescriptor));
            if (noAlias) {
                methodVisitor.visitMethodInsn(INVOKESTATIC, "org/bukkit/configuration/serialization/ConfigurationSerialization", "registerClass", "(Ljava/lang/Class;)V", false);
            } else {
                methodVisitor.visitLdcInsn(serializableAs);
                methodVisitor.visitMethodInsn(INVOKESTATIC, "org/bukkit/configuration/serialization/ConfigurationSerialization", "registerClass", "(Ljava/lang/Class;Ljava/lang/String;)V", false);
            }
            Label label1 = new Label();
            methodVisitor.visitLabel(label1);
            methodVisitor.visitInsn(RETURN);
            methodVisitor.visitMaxs(noAlias ? 1 : 2, 0);
            methodVisitor.visitEnd();

            //generate class initializer which calls registerWithConfigurationSerialization
            if (!alreadyHasClassInitializer && registerAt == InjectionPoint.CLASS_INITIALIZER) {
                MethodVisitor mvStaticInit = this.visitMethod(ACC_STATIC, CLASS_INIT_NAME, "()V", null, null);
                mvStaticInit.visitCode();
                mvStaticInit.visitMethodInsn(INVOKESTATIC, className, REGISTER_NAME, REGISTER_DESCRIPTOR, classIsInterface);
                mvStaticInit.visitInsn(RETURN);
                mvStaticInit.visitMaxs(0, 0);
                mvStaticInit.visitEnd();
            }
        }

        super.visitEnd();
    }

}
