package xyz.janboerman.scalaloader.bytecode;

import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * This class is NOT part of the public API!
 */
public final class LocalVariableTable implements Iterable<LocalVariable> {

    private int maxCount;
    private final Set<LocalVariable> localVariables;
    private final ArrayList<LocalVariable> frameData;

    public LocalVariableTable() {
        this.localVariables = new LinkedHashSet<>();
        this.frameData = new ArrayList<>(0);
        this.maxCount = 0;
    }

    public void add(LocalVariable localVariable) {
        final int tableIndex = localVariable.tableIndex;
        assert 0 <= tableIndex : "index in the local variable table below 0";
        //assert that the new local variable's index is not more than 1 above the currently known highest local variable.
        assert tableIndex <= maxLocals() : "local variable " + localVariable + " is more than 1 higher than the currently known highest local variable in the table " + this + ", maxLocals = " + maxLocals();
        //assert that the local variable is replaces an older one, or is added at the 'next' index.
        assert tableIndex <= localVariables.size() : "local variable " + localVariable + " does not 'replace' another, nor, is it the 'next' local variable in the table " + this;

        localVariables.add(localVariable);
        maxCount = Math.max(maxCount, tableIndex + 1);
        addFrame(localVariable);

        assert frameData.stream().noneMatch(Objects::isNull) : "a local variable in the frame is null";
    }

    public void add(LocalVariable... localVariables) {
        for (LocalVariable localVariable : localVariables) {
            this.localVariables.add(localVariable);
            this.maxCount = Math.max(maxCount, localVariable.tableIndex + 1);
            addFrame(localVariable);
        }

        //assert that there are no gaps in the local variable table
        assert IntStream.range(0, maxLocals()).allMatch(index -> this.localVariables.stream().anyMatch(lvd -> lvd.tableIndex == index));
        //assert that no local variable in the frame is null
        assert frameData.stream().allMatch(Objects::nonNull);
        //assert that the frame data is consistent
        assert IntStream.range(0, frameData.size()).allMatch(index -> frameData.get(index).tableIndex == index);
    }

    private void addFrame(LocalVariable localVariable) {
        int tableIndex = localVariable.tableIndex;
        if (tableIndex < frameData.size()) {
            //replace
            frameData.set(tableIndex, localVariable);
        } else if (tableIndex == frameData.size()){
            //append
            frameData.add(localVariable);
        } else {
            //add nulls and replace later
            for (int i = frameData.size(); i < tableIndex; i++) {
                frameData.add(null);
            }
            frameData.add(localVariable);
        }

        //callers need to assert that our frame data is consistent.
    }

    public int maxLocals() {
        return maxCount;
    }

    public int currentLocals() {
        return localVariables.size();
    }

    public void removeFrames(int howMany) {
        int initialLastIndex = frameData.size() - 1;
        for (int i = 0; i < howMany; i++) {
            int index = initialLastIndex - i;
            frameData.remove(index);
        }
    }

    public void removeFramesFromIndex(int index) {
        int size = frameData.size();
        for (int i = size - 1; i >= index; i--) {
            frameData.remove(i);
        }
    }

    public void removeFrame(LocalVariable localVariable) {
        frameData.remove(localVariable);
    }

    @Override
    public Iterator<LocalVariable> iterator() {
        return getLocalVariables().iterator();
    }

    public Set<LocalVariable> getLocalVariables() {
        return Collections.unmodifiableSet(localVariables);
    }

    public List<LocalVariable> getFrameLocals() {
        return Collections.unmodifiableList(frameData);
    }

    public Object[] frame() {
        return frameData.stream().map(localVariable -> {
            String desc = localVariable.descriptor;
            switch (desc) {
                case "B":
                case "S":
                case "I":
                case "C":
                case "Z":
                    return Opcodes.INTEGER;
                case "J":
                    return Opcodes.LONG;
                case "F":
                    return Opcodes.FLOAT;
                case "D":
                    return Opcodes.DOUBLE;
                case "V":
                    return Opcodes.TOP;
                default:
                    return Type.getType(desc).getInternalName();
            }
        }).toArray();
    }

    @Override
    public String toString() {
        return localVariables.toString();
    }

}
