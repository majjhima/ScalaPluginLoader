package xyz.janboerman.scalaloader.configurationserializable.transform;

import org.objectweb.asm.*;
import org.objectweb.asm.Type;  //explicitly import because there is also java.lang.reflect.Type
import static org.objectweb.asm.Opcodes.*;

import static xyz.janboerman.scalaloader.bytecode.AsmConstants.*;
import xyz.janboerman.scalaloader.bytecode.*;
import xyz.janboerman.scalaloader.compat.Compat;
import static xyz.janboerman.scalaloader.configurationserializable.transform.ConfigurationSerializableTransformations.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

class Conversions {

    private Conversions() {}

    static void toSerializedType(MethodVisitor methodVisitor, String descriptor, String signature, int localVariableIndex, Label start, Label end, LocalVariableTable localVariables, OperandStack operandStack) {

        TypeSignature typeSignature = signature == null ? TypeSignature.ofDescriptor(descriptor) : TypeSignature.ofDescriptor(signature);

        //detect arrays
        if (TypeSignature.ARRAY.equals(typeSignature.getTypeName())) {
            //convert array to java.util.List.
            arrayToSerializedType(methodVisitor, typeSignature, operandStack, localVariables, localVariableIndex);
            return;
        } //else if (collection types)

        //TODO implement conversion of elements of java.util.List, java.util.Set and java.util.Map later.
        //TODO look at their signature!
        //TODO the generated code is quite similar to the array-code!

        //TODO just like conversion for arrays, implement conversion for scala collection types (both mutable and immutable) (including: tuples, Option, Either, Try)

        switch (descriptor) {
            //primitives
            case "B": //interestingly, I can just call a method that takes an int with a byte.
            case "S": //interestingly, I can just call a method that takes an int with a short.
            case "I": //so we just fall-through
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                operandStack.replaceTop(Integer_TYPE);
                break;
            case "J":
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "toString", "(J)Ljava/lang/String;", false);
                operandStack.replaceTop(STRING_TYPE);
                break;
            case "F":
                methodVisitor.visitInsn(F2D); //convert float to double and fall-through to Double.valueOf(double)
                operandStack.replaceTop(Type.DOUBLE_TYPE);
            case "D":
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                operandStack.replaceTop(Double_TYPE);
                break;
            case "C":
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "toString", "(C)Ljava/lang/String;", false);
                operandStack.replaceTop(STRING_TYPE);
                break;
            case "Z":
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                operandStack.replaceTop(Boolean_TYPE);
                break;

            //boxed primitives
            case "Ljava/lang/Byte;":
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Byte", "intValue", "()I", false);
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                operandStack.replaceTop(Type.INT_TYPE);
                operandStack.replaceTop(Integer_TYPE);
                break;
            case "Ljava/lang/Short;":
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Short", "intValue", "()I", false);
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                operandStack.replaceTop(Type.INT_TYPE);
                operandStack.replaceTop(Integer_TYPE);
                break;
            case "Ljava/lang/Integer;":
                break;
            case "Ljava/lang/Long;":
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "toString", "()Ljava/lang/String;", false);
                operandStack.replaceTop(STRING_TYPE);
                break;
            case "Ljava/lang/Float":
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Float", "doubleValue", "()D", false);
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                operandStack.replaceTop(Type.DOUBLE_TYPE);
                operandStack.replaceTop(Double_TYPE);
                break;
            case "Ljava/lang/Double;":
                break;
            case "Ljava/lang/Character;":
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "toString", "()Ljava/lang/String;", false);
                operandStack.replaceTop(STRING_TYPE);
                break;
            case "Ljava/lang/Boolean;":
                break;

            //other reference types
            //String, List, Set and Map are a no-op (just like Integer, Boolean and Double)
            //the same holds for any type that implements ConfigurationSerializable

            case "Ljava/math/BigInteger;":
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/math/BigInteger", "toString", "()Ljava/lang/String;", false);
                operandStack.replaceTop(STRING_TYPE);
                break;
            case "Ljava/math/BigDecimal;":
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/math/BigDecimal", "toString", "()Ljava/lang/String;", false);
                operandStack.replaceTop(STRING_TYPE);
                break;
            case "Ljava/util/UUID;":
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/UUID", "toString", "()Ljava/lang/String;", false);
                operandStack.replaceTop(STRING_TYPE);
                break;

            //in any other case: assume the type is ConfigurationSerializable and just no-op!
            default:
                //TODO insert a cast?
                //TODO should not be necessary, we are serializing, not deserializing.
                break;


            //TODO something like Date, DateFormat, Instant, LocalDateTime, other Time-api related things?
            //TODO Locale, CharSet?
        }

    }

    private static void arrayToSerializedType(MethodVisitor methodVisitor, TypeSignature arrayTypeSignature, OperandStack operandStack, LocalVariableTable localVariableTable, int localVariableIndex) {

        assert TypeSignature.ARRAY.equals(arrayTypeSignature.getTypeName()) : "not an array";
        final TypeSignature componentTypeSignature = arrayTypeSignature.getTypeArgument(0);
        final Type arrayType = Type.getType(arrayTypeSignature.toDescriptor());
        final Type arrayComponentType = Type.getType(componentTypeSignature.toDescriptor());
        final TypeSignature serializedComponentTypeSignature = serializedType(componentTypeSignature);
        final Type serializedComponentType = Type.getType(serializedComponentTypeSignature.toDescriptor());

        final Label start = new Label();
        final Label end = new Label();
        methodVisitor.visitLabel(start);

        //store array in local variable
        methodVisitor.visitTypeInsn(CHECKCAST, arrayType.getInternalName());        operandStack.replaceTop(arrayType);
        final int arrayIndex = localVariableIndex++;
        final LocalVariable array = new LocalVariable("array", arrayTypeSignature.toDescriptor(), arrayTypeSignature.toSignature(), start, end, arrayIndex);
        localVariableTable.add(array);
        methodVisitor.visitVarInsn(ASTORE, arrayIndex);                             operandStack.pop();

        //make list and store it in a local variable
        final int listIndex = localVariableIndex++;
        methodVisitor.visitTypeInsn(NEW, "java/util/ArrayList");                operandStack.push(ARRAYLIST_TYPE);
        methodVisitor.visitInsn(DUP);                                               operandStack.push(ARRAYLIST_TYPE);
        methodVisitor.visitVarInsn(ALOAD, arrayIndex);                              operandStack.push(arrayType);
        methodVisitor.visitInsn(ARRAYLENGTH);                                       operandStack.replaceTop(Type.INT_TYPE);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false);    operandStack.pop(2);
        final LocalVariable list = new LocalVariable("list", "Ljava/util/List;", "Ljava/util/List<" + serializedComponentTypeSignature.toSignature() + ">;", start, end, listIndex);
        localVariableTable.add(list);
        methodVisitor.visitVarInsn(ASTORE, listIndex);                              operandStack.pop();

        //make size and index local variables, additionally create an extra local variable for the array!
        final int sameArrayIndex = localVariableIndex++;
        final int sizeIndex = localVariableIndex++;
        final int indexIndex = localVariableIndex++;
        methodVisitor.visitVarInsn(ALOAD, arrayIndex);                              operandStack.push(arrayType);
        final LocalVariable sameArray = new LocalVariable("sameArray", arrayTypeSignature.toDescriptor(), arrayTypeSignature.toSignature(), start, end, sameArrayIndex);
        localVariableTable.add(sameArray);
        methodVisitor.visitVarInsn(ASTORE, sameArrayIndex);                         operandStack.pop();
        methodVisitor.visitVarInsn(ALOAD, sameArrayIndex);                          operandStack.push(arrayType);
        methodVisitor.visitInsn(ARRAYLENGTH);                                       operandStack.replaceTop(Type.INT_TYPE);
        final LocalVariable size = new LocalVariable("size", "I", null, start, end, sizeIndex);
        localVariableTable.add(size);
        methodVisitor.visitVarInsn(ISTORE, sizeIndex);                              operandStack.pop();
        methodVisitor.visitInsn(ICONST_0);                                          operandStack.push(Type.INT_TYPE);
        final LocalVariable index = new LocalVariable("index", "I", null, start, end, indexIndex);
        localVariableTable.add(index);
        methodVisitor.visitVarInsn(ISTORE, indexIndex);                             operandStack.pop();

        //loop body
        final Label jumpBackTarget = new Label();
        final Label endOfLoopTarget = new Label();

        methodVisitor.visitLabel(jumpBackTarget);
        final Object[] localsFrame = localVariableTable.frame();
        final Object[] stackFrame = operandStack.frame();
        methodVisitor.visitFrame(F_FULL, localsFrame.length, localsFrame, stackFrame.length, stackFrame);
        //compare index to size
        methodVisitor.visitVarInsn(ILOAD, indexIndex);                              operandStack.push(Type.INT_TYPE);
        methodVisitor.visitVarInsn(ILOAD, sizeIndex);                               operandStack.push(Type.INT_TYPE);
        methodVisitor.visitJumpInsn(IF_ICMPGE, endOfLoopTarget);                    operandStack.pop(2);
        //load the element
        methodVisitor.visitVarInsn(ALOAD, sameArrayIndex);                          operandStack.push(arrayType);
        methodVisitor.visitVarInsn(ILOAD, indexIndex);                              operandStack.push(Type.INT_TYPE);
        methodVisitor.visitInsn(arrayType.getOpcode(IALOAD));                       operandStack.replaceTop(2, arrayComponentType);

        //convert
        final int elementIndex = localVariableIndex++;
        final Label bodyStart = new Label();
        final Label bodyEnd = new Label();
        methodVisitor.visitLabel(bodyStart);
        toSerializedType(methodVisitor, componentTypeSignature.toDescriptor(), componentTypeSignature.toSignature(), localVariableIndex, bodyStart, bodyEnd, localVariableTable, operandStack);
        methodVisitor.visitLabel(bodyEnd);
        final LocalVariable element = new LocalVariable("element", serializedComponentTypeSignature.toDescriptor(), serializedComponentTypeSignature.toSignature(), jumpBackTarget, endOfLoopTarget, elementIndex);
        localVariableTable.add(element);
        methodVisitor.visitVarInsn(serializedComponentType.getOpcode(ISTORE), elementIndex);        operandStack.pop();

        //call list.add
        methodVisitor.visitVarInsn(ALOAD, listIndex);                               operandStack.push(LIST_TYPE);
        methodVisitor.visitVarInsn(ALOAD, elementIndex);                            operandStack.push(serializedComponentType);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);     operandStack.replaceTop(2, Type.BOOLEAN_TYPE);
        methodVisitor.visitInsn(POP);                                               operandStack.pop();

        //index++;
        methodVisitor.visitIincInsn(indexIndex, 1);
        methodVisitor.visitJumpInsn(GOTO, jumpBackTarget);

        methodVisitor.visitLabel(endOfLoopTarget);
        localVariableTable.removeFramesFromIndex(elementIndex);
        assert Arrays.equals(localsFrame, localVariableTable.frame()) : "local variables differ!";
        assert Arrays.equals(stackFrame, operandStack.frame()) : "stack operands differ!";
        methodVisitor.visitFrame(F_FULL, localsFrame.length, localsFrame, stackFrame.length, stackFrame);

        //load the list again, and continue execution.
        methodVisitor.visitVarInsn(ALOAD, listIndex);                               operandStack.push(LIST_TYPE);
        methodVisitor.visitLabel(end);
        localVariableTable.removeFramesFromIndex(arrayIndex);
    }

    private static TypeSignature serializedType(TypeSignature liveType) {
        String internalName = liveType.getTypeName();
        List<TypeSignature> typeArguments = liveType.getTypeArguments();

        if (TypeSignature.ARRAY.equals(internalName))
            return new TypeSignature("java/util/List", typeArguments.stream()
                    .map(Conversions::serializedType)
                    .collect(Collectors.toList()));

        switch (internalName) {
            case "B": case "java/lang/Byte":
            case "S": case "java/lang/Short":
            case "I": case "java/lang/Integer":
                return new TypeSignature("java/lang/Integer", Compat.emptyList());
            case "F": case "java/lang/Float":
            case "D": case "java/lang/Double:":
                return new TypeSignature("java/lang/Double", Compat.emptyList());
            case "C": case "java/lang/Character":
            case "J": case "java/lang/Long":
                return new TypeSignature("java/lang/String", Compat.emptyList());
            case "Z": case "java/lang/Boolean":
                return new TypeSignature("java/lang/Boolean", Compat.emptyList());

            case "java/util/UUID":
            case "java/math/BigInteger":
            case "java/math/BigDecimal":
                return new TypeSignature("java/lang/String", Compat.emptyList());
        }

        return new TypeSignature(internalName, typeArguments.stream()
                .map(Conversions::serializedType)
                .collect(Collectors.toList()));
    }


    static void toLiveType(MethodVisitor methodVisitor, String descriptor, String signature, int localVariableIndex, Label start, Label end, LocalVariableTable localVariables, OperandStack operandStack) {

        final TypeSignature typeSignature = signature != null ? TypeSignature.ofSignature(signature) : TypeSignature.ofDescriptor(descriptor);

        if (!typeSignature.getTypeArguments().isEmpty()) {
            if (TypeSignature.ARRAY.equals(typeSignature.getTypeName())) {
                //generate code for transforming arrays to lists and their elements
                arrayToLiveType(methodVisitor, typeSignature, operandStack, localVariables, localVariableIndex);
                return;
            } else {
                //TODO generate code converting elements of collections and maps
            }
        }


        switch (descriptor) {
            //primitives
            case "B":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "byteValue", "()B", false);
                operandStack.replaceTop(Integer_TYPE);
                operandStack.replaceTop(Type.BYTE_TYPE);
                break;
            case "S":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "shortValue", "()S", false);
                operandStack.replaceTop(Integer_TYPE);
                operandStack.replaceTop(Type.SHORT_TYPE);
                break;
            case "I":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                operandStack.replaceTop(Integer_TYPE);
                operandStack.replaceTop(Type.INT_TYPE);
                break;
            case "J":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/String");
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "parseLong", "(Ljava/lang/String;)J", false);
                operandStack.replaceTop(STRING_TYPE);
                operandStack.replaceTop(Type.LONG_TYPE);
                break;
            case "F":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Double");
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "floatValue", "()F", false);
                operandStack.replaceTop(Double_TYPE);
                operandStack.replaceTop(Type.FLOAT_TYPE);
                break;
            case "D":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Double");
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
                operandStack.replaceTop(Double_TYPE);
                operandStack.replaceTop(Type.DOUBLE_TYPE);
                break;
            case "C":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/String");
                methodVisitor.visitInsn(ICONST_0);
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
                operandStack.replaceTop(STRING_TYPE);
                operandStack.push(Type.INT_TYPE);
                operandStack.replaceTop(2, Type.CHAR_TYPE);
                break;
            case "Z":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                operandStack.replaceTop(Boolean_TYPE);
                operandStack.replaceTop(Type.BOOLEAN_TYPE);
                break;

            //boxed primitives
            case "Ljava/lang/Byte;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "byteValue", "()B", false);
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                operandStack.replaceTop(Integer_TYPE);
                operandStack.replaceTop(Type.BYTE_TYPE);
                operandStack.replaceTop(Byte_TYPE);
                break;
            case "Ljava/lang/Short;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "shortValue", "()S", false);
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                operandStack.replaceTop(Integer_TYPE);
                operandStack.replaceTop(Type.SHORT_TYPE);
                operandStack.replaceTop(Short_TYPE);
                break;
            case "Ljava/lang/Integer;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                operandStack.replaceTop(Integer_TYPE);
                break;
            case "Ljava/lang/Long;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/String");
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(Ljava/lang/String;)Ljava/lang/Long;", false);
                operandStack.replaceTop(STRING_TYPE);
                operandStack.replaceTop(Long_TYPE);
                break;
            case "Ljava/lang/Float;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Double");
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "floatValue", "()F", false);
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                operandStack.replaceTop(Double_TYPE);
                operandStack.replaceTop(Type.FLOAT_TYPE);
                operandStack.replaceTop(Float_TYPE);
                break;
            case "Ljava/lang/Double;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Double");
                operandStack.replaceTop(Double_TYPE);
                break;
            case "Ljava/lang/Character":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/String");
                methodVisitor.visitInsn(ICONST_0);
                methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                operandStack.replaceTop(STRING_TYPE);
                operandStack.push(Type.INT_TYPE);
                operandStack.replaceTop(2, Type.CHAR_TYPE);
                operandStack.replaceTop(Character_TYPE);
                break;
            case "Ljava/lang/Boolean;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                operandStack.replaceTop(Boolean_TYPE);
                break;

            //non-supported reference types
            case "Ljava/math/BigInteger;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/String");
                operandStack.replaceTop(STRING_TYPE);                                                               //stack: [..., string]
                methodVisitor.visitTypeInsn(NEW, "java/math/BigInteger");
                operandStack.push(BIGINTEGER_TYPE);                                                                 //stack: [..., string, biginteger]
                methodVisitor.visitInsn(DUP_X1);
                operandStack.pop(2);    operandStack.push(BIGINTEGER_TYPE, STRING_TYPE, BIGINTEGER_TYPE);   //stack: [..., biginteger, string, biginteger]
                methodVisitor.visitInsn(SWAP);
                operandStack.pop(2);    operandStack.push(BIGINTEGER_TYPE, STRING_TYPE);                    //stack: [..., biginteger, biginteger, string]
                methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/math/BigInteger", "<init>", "(Ljava/lang/String;)V", false);
                operandStack.pop(2);                                                                        //stack: [..., biginteger]
                break;
            case "Ljava/math/BigDecimal;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/String");
                operandStack.replaceTop(STRING_TYPE);                                                               //stack: [..., string]
                methodVisitor.visitTypeInsn(NEW, "java/math/BigDecimal");
                operandStack.push(BIGDECIMAL_TYPE);                                                                 //stack: [..., string, bigdecimal]
                methodVisitor.visitInsn(DUP_X1);
                operandStack.pop(2);    operandStack.push(BIGDECIMAL_TYPE, STRING_TYPE, BIGDECIMAL_TYPE);   //stack: [..., bigdecimal, string, bigdecimal]
                methodVisitor.visitInsn(SWAP);
                operandStack.pop(2);    operandStack.push(BIGDECIMAL_TYPE, STRING_TYPE);                    //stack: [..., bigdecimal, bigdecimal, string]
                methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/math/BigDecimal", "<init>", "(Ljava/lang/String;)V", false);
                operandStack.pop(2);                                                                        //stack: [..., bigdecimal]
                break;
            case "Ljava/util/UUID;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/String");
                methodVisitor.visitMethodInsn(INVOKESTATIC, "java/util/UUID", "fromString", "(Ljava/lang/String;)Ljava/util/UUID;", false);
                operandStack.replaceTop(STRING_TYPE);
                operandStack.replaceTop(UUID_TYPE);
                break;

            //supported reference types
            case "Ljava/lang/String;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/String");
                operandStack.replaceTop(STRING_TYPE);
                break;

            //TODO convert elements
            case "Ljava/util/List;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/util/List");
                operandStack.replaceTop(LIST_TYPE);
                break;
            case "Ljava/util/Set;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/util/Set");
                operandStack.replaceTop(SET_TYPE);
                break;
            case "Ljava/util/Map;":
                methodVisitor.visitTypeInsn(CHECKCAST, "java/util/Map");
                operandStack.push(MAP_TYPE);
                break;

            default:
                //assume ConfigurationSerializable, just cast.
                Type type = Type.getType(descriptor);
                methodVisitor.visitTypeInsn(CHECKCAST, type.getInternalName());
                operandStack.replaceTop(type);
                break;
        }
    }

    private static void arrayToLiveType(MethodVisitor methodVisitor, TypeSignature arrayTypeSignature, OperandStack operandStack, LocalVariableTable localVariableTable, int localVariableIndex) {

        assert TypeSignature.ARRAY.equals(arrayTypeSignature.getTypeName()) : "not an array";

        final TypeSignature componentTypeSignature = arrayTypeSignature.getTypeArgument(0);
        final Type arrayType = Type.getType(arrayTypeSignature.toDescriptor());
        final Type componentType = Type.getType(componentTypeSignature.toDescriptor());
        final TypeSignature listTypeSignature = serializedType(arrayTypeSignature);

        final Label start = new Label();
        final Label end = new Label();
        methodVisitor.visitLabel(start);

        //take operand on top of the stack, cast it to list, store it in a local variable
        final int listIndex = localVariableIndex++;
        methodVisitor.visitTypeInsn(CHECKCAST, "java/util/List");                       operandStack.replaceTop(LIST_TYPE);                                         //[..., list]
        final LocalVariable list = new LocalVariable("list", "Ljava/util/List;", listTypeSignature.toSignature(), start, end, listIndex);
        localVariableTable.add(list);
        methodVisitor.visitVarInsn(ASTORE, listIndex);                                  operandStack.pop();                                                             //[...]

        //get the size, instantiate a new array, store it in a local variable
        final int arrayIndex = localVariableIndex++;
        methodVisitor.visitVarInsn(ALOAD, listIndex);                                   operandStack.push(LIST_TYPE);                                                   //[..., list]
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true);      operandStack.replaceTop(Type.INT_TYPE);     //[..., size]
        visitNewArray(arrayTypeSignature, methodVisitor);                               operandStack.replaceTop(arrayType);                                             //[..., array]
        final LocalVariable array = new LocalVariable("array", arrayTypeSignature.toDescriptor(), arrayTypeSignature.toSignature(), start, end, arrayIndex);
        localVariableTable.add(array);
        methodVisitor.visitVarInsn(ASTORE, arrayIndex);                                 operandStack.pop();                                                             //[...]

        //instantiate index
        final int indexIndex = localVariableIndex++;
        methodVisitor.visitInsn(ICONST_0);                                              operandStack.push(Type.INT_TYPE);                                               //[..., index]
        final LocalVariable index = new LocalVariable("index", "I", null, start, end, indexIndex);
        localVariableTable.add(index);
        methodVisitor.visitVarInsn(ISTORE, indexIndex);                                 operandStack.pop();                                                             //[...]

        //loop body
        final Label jumpBackTarget = new Label();
        final Label endOfLoopTarget = new Label();
        methodVisitor.visitLabel(jumpBackTarget);
        final Object[] localsFrame = localVariableTable.frame();
        final Object[] stackFrame = operandStack.frame();
        methodVisitor.visitFrame(F_FULL, localsFrame.length, localsFrame, stackFrame.length, stackFrame);
        methodVisitor.visitVarInsn(ILOAD, indexIndex);                                  operandStack.push(Type.INT_TYPE);                                               //[..., index]
        methodVisitor.visitVarInsn(ALOAD, arrayIndex);                                  operandStack.push(arrayType);                                                   //[..., index, array]
        methodVisitor.visitInsn(ARRAYLENGTH);                                           operandStack.replaceTop(Type.INT_TYPE);                                         //[..., index, length]
        //if (index < array.length) continue loop body
        methodVisitor.visitJumpInsn(IF_ICMPGE, endOfLoopTarget);                        operandStack.pop(2);                                                    //[...]

        //prepare array and index so that we can use (B/S/I/J/Z/C/F/D/A)ASTORE later
        methodVisitor.visitVarInsn(ALOAD, arrayIndex);                                  operandStack.push(arrayType);                                                   //[..., array]
        methodVisitor.visitVarInsn(ILOAD, indexIndex);                                  operandStack.push(Type.INT_TYPE);                                               //[..., array, index]
        //call list.get(index)
        methodVisitor.visitVarInsn(ALOAD, listIndex);                                   operandStack.push(LIST_TYPE);                                                   //[..., array, index, list]
        methodVisitor.visitVarInsn(ILOAD, indexIndex);                                  operandStack.push(Type.INT_TYPE);                                               //[..., array, index, list, index]
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);         operandStack.replaceTop(2, OBJECT_TYPE);    //[..., array, index, object]

        //convert
        final Label bodyStart = new Label(), bodyEnd = new Label();
        methodVisitor.visitLabel(bodyStart);
        toLiveType(methodVisitor, componentTypeSignature.toDescriptor(), componentTypeSignature.toSignature(), localVariableIndex, bodyStart, bodyEnd, localVariableTable, operandStack);                           //[..., array, index, element]
        methodVisitor.visitLabel(bodyEnd);
        //store in the array (that we loaded earlier before list.get)
        methodVisitor.visitInsn(componentType.getOpcode(IASTORE));                      operandStack.pop(3);                                                    //[...]

        //index++
        methodVisitor.visitIincInsn(indexIndex, 1);
        methodVisitor.visitJumpInsn(GOTO, jumpBackTarget);

        //(index < size) is no longer true
        methodVisitor.visitLabel(endOfLoopTarget);                                                                                                                      //[...]
        localVariableTable.removeFramesFromIndex(localVariableIndex);
        assert Arrays.equals(localsFrame, localVariableTable.frame()) : "local variables differ!";
        assert Arrays.equals(stackFrame, operandStack.frame()) : "stack operands differ!";
        methodVisitor.visitFrame(F_FULL, localsFrame.length, localsFrame, stackFrame.length, stackFrame);

        //load the array again, and continue execution
        methodVisitor.visitVarInsn(ALOAD, arrayIndex);                                  operandStack.push(arrayType);                                                   //[..., array]
        methodVisitor.visitLabel(end);
        localVariableTable.removeFramesFromIndex(listIndex);
    }

    private static void visitNewArray(TypeSignature theArrayType, MethodVisitor mv) {
        TypeSignature ofWhat = theArrayType.getTypeArgument(0);
        switch (ofWhat.getTypeName()) {
            case "B":   mv.visitIntInsn(NEWARRAY, T_BYTE);                      break;
            case "S":   mv.visitIntInsn(NEWARRAY, T_SHORT);                     break;
            case "I":   mv.visitIntInsn(NEWARRAY, T_INT);                       break;
            case "J":   mv.visitIntInsn(NEWARRAY, T_LONG);                      break;
            case "F":   mv.visitIntInsn(NEWARRAY, T_FLOAT);                     break;
            case "D":   mv.visitIntInsn(NEWARRAY, T_DOUBLE);                    break;
            case "Z":   mv.visitIntInsn(NEWARRAY, T_BOOLEAN);                   break;
            case "C":   mv.visitIntInsn(NEWARRAY, T_CHAR);                      break;
            default:    mv.visitTypeInsn(ANEWARRAY, ofWhat.getTypeName());      break;
        }
    }


    static String boxedType(String type) {
        switch (type) {
            case "B": return javaLangByte_TYPE;
            case "S": return javaLangShort_TYPE;
            case "I": return javaLangInteger_TYPE;
            case "J": return javaLangLong_TYPE;
            case "C": return javaLangCharacter_TYPE;
            case "F": return javaLangFloat_TYPE;
            case "D": return javaLangDouble_TYPE;
            case "Z": return javaLangBoolean_TYPE;
            case "V": return javaLangVoid_TYPE;
        }

        return type;
    }

    static String boxedDescriptor(String descriptor) {
        switch (descriptor) {
            case "B": return javaLangByte_DESCRIPTOR;
            case "S": return javaLangShort_DESCRIPTOR;
            case "I": return javaLangInteger_DESCRIPTOR;
            case "J": return javaLangLong_DESCRIPTOR;
            case "C": return javaLangCharacter_DESCRIPTOR;
            case "F": return javaLangFloat_DESCRIPTOR;
            case "D": return javaLangDouble_DESCRIPTOR;
            case "Z": return javaLangBoolean_DESCRIPTOR;
            case "V": return javaLangVoid_DESCRIPTOR;
        }

        return descriptor;
    }

    //TODO arrays of boxed primitives
    //TODO arrays of other reference types that I want to support out of the box:
    //TODO BigInteger, BigDecimal, String and UUID
    //TODO ACTUALLY - I think it's probably better to generate that bytecode in the classfile itself! (in case of nested arrays!)
    //TODO arrays of enums?
    //TODO arrays of other configurationserializable types

}
