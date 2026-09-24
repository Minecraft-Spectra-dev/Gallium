package cn.spectra.gallium.glowoutline.capture;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Identity-based evidence that every registered source submission reached its native renderer. */
public final class SourceSubmissionLedger {
    private static final class Receipt {
        final Set<Object> expected=Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> observed=Collections.newSetFromMap(new IdentityHashMap<>());
        boolean rejected;
    }
    private final int stateLimit,sourceLimit;
    private final IdentityHashMap<Object,Receipt> states=new IdentityHashMap<>();
    private final IdentityHashMap<Object,Object> owners=new IdentityHashMap<>();
    private boolean invalid,closed;
    private long revision;
    public SourceSubmissionLedger(int stateLimit,int sourceLimit) {
        if(stateLimit<1 || sourceLimit<1)throw new IllegalArgumentException("Positive source limits required");
        this.stateLimit=stateLimit;this.sourceLimit=sourceLimit;
    }
    private Receipt receipt(Object owner) {
        if(owner==null || closed || invalid)return null;
        var value=states.get(owner);
        if(value==null){if(states.size()>=stateLimit){invalid=true;revision++;return null;}value=new Receipt();states.put(owner,value);}
        return value;
    }
    public boolean register(Object owner,Object source) {
        var receipt=receipt(owner);
        if(receipt==null || source==null){reject(owner);return false;}
        if(owners.containsKey(source)){reject(owners.get(source));reject(owner);return false;}
        if(owners.size()>=sourceLimit){invalid=true;revision++;return false;}
        owners.put(source,owner);receipt.expected.add(source);revision++;return !receipt.rejected;
    }
    public Object owner(Object source) { return closed || invalid ? null : owners.get(source); }
    public void observed(Object source) {
        var owner=owner(source);var receipt=owner==null?null:states.get(owner);
        if(receipt!=null && !receipt.rejected && receipt.observed.add(source))revision++;
    }
    public void reject(Object owner) { var receipt=receipt(owner);if(receipt!=null && !receipt.rejected){receipt.rejected=true;revision++;} }
    public boolean complete(Object owner) {
        var receipt=states.get(owner);
        return !closed && !invalid && receipt!=null && !receipt.rejected && !receipt.expected.isEmpty()
                && receipt.expected.size()==receipt.observed.size();
    }
    public long revision() { return revision; }
    public int expected(Object owner) { var r=states.get(owner);return r==null?0:r.expected.size(); }
    public int observedCount(Object owner) { var r=states.get(owner);return r==null?0:r.observed.size(); }
    public void close() { closed=true;revision++;states.clear();owners.clear(); }
}
