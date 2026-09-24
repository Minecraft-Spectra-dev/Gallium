package cn.spectra.gallium.glowoutline.capture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SourceSubmissionLedgerTest {
    @Test void everyOriginalIdentityMustBeObserved() {
        var ledger=new SourceSubmissionLedger(4,8);var owner=new Object();var a=new Object();var b=new Object();
        assertTrue(ledger.register(owner,a));assertTrue(ledger.register(owner,b));ledger.observed(a);
        assertFalse(ledger.complete(owner));ledger.observed(new Object());assertFalse(ledger.complete(owner));
        ledger.observed(b);assertTrue(ledger.complete(owner));ledger.observed(b);assertTrue(ledger.complete(owner));
    }
    @Test void equalValuesRemainDistinctOwnersAndSubmissions() {
        var ledger=new SourceSubmissionLedger(4,8);var owner=new String("owner");var equalOwner=new String("owner");var source=new String("source");
        ledger.register(owner,source);ledger.observed(new String("source"));assertFalse(ledger.complete(owner));
        ledger.observed(source);assertTrue(ledger.complete(owner));assertFalse(ledger.complete(equalOwner));
    }
    @Test void conflictingOwnershipInvalidatesBothReceipts() {
        var ledger=new SourceSubmissionLedger(4,8);var a=new Object();var b=new Object();var source=new Object();
        ledger.register(a,source);ledger.observed(source);assertTrue(ledger.complete(a));
        assertFalse(ledger.register(b,source));assertFalse(ledger.complete(a));assertFalse(ledger.complete(b));
    }
    @Test void duplicateRegistrationIsNotAnExtraRenderingLayer() {
        var ledger=new SourceSubmissionLedger(4,8);var owner=new Object();var source=new Object();
        ledger.register(owner,source);ledger.observed(source);assertFalse(ledger.register(owner,source));assertFalse(ledger.complete(owner));
    }
    @Test void unsupportedAndMissingSourcesCannotBecomeComplete() {
        var ledger=new SourceSubmissionLedger(4,8);var owner=new Object();var source=new Object();
        assertFalse(ledger.complete(owner));ledger.reject(owner);ledger.register(owner,source);ledger.observed(source);assertFalse(ledger.complete(owner));
        assertFalse(ledger.register(new Object(),null));
    }
    @Test void capacityFailureInvalidatesTheFrameRatherThanHidingMissingWork() {
        var ledger=new SourceSubmissionLedger(1,1);var owner=new Object();var source=new Object();ledger.register(owner,source);ledger.observed(source);
        assertFalse(ledger.register(owner,new Object()));assertFalse(ledger.complete(owner));
        var states=new SourceSubmissionLedger(1,4);states.register(owner,source);states.observed(source);assertFalse(states.register(new Object(),new Object()));assertFalse(states.complete(owner));
    }
    @Test void closeReleasesSourceReferencesAndCannotBeReused() {
        var ledger=new SourceSubmissionLedger(4,8);var owner=new Object();var source=new Object();ledger.register(owner,source);ledger.observed(source);ledger.close();ledger.close();
        assertNull(ledger.owner(source));assertFalse(ledger.complete(owner));assertFalse(ledger.register(owner,source));assertEquals(0,ledger.expected(owner));
    }
    @Test void cachedProofRevisionChangesWhenNewWorkOrRejectionArrives() {
        var ledger=new SourceSubmissionLedger(4,8);var owner=new Object();var source=new Object();
        ledger.register(owner,source);long before=ledger.revision();ledger.observed(source);assertTrue(ledger.revision()>before);
        long complete=ledger.revision();ledger.observed(source);ledger.observed(new Object());assertEquals(complete,ledger.revision());
        ledger.register(owner,new Object());assertTrue(ledger.revision()>complete);assertFalse(ledger.complete(owner));
        long registered=ledger.revision();ledger.reject(owner);assertTrue(ledger.revision()>registered);
    }
    @Test void conflictWithAlreadyRejectedOwnerInvalidatesCachedProof() {
        var ledger=new SourceSubmissionLedger(4,8);
        var rejectedOwner=new Object();var source=new Object();
        ledger.register(rejectedOwner,source);ledger.reject(rejectedOwner);
        var completeOwner=new Object();var otherSource=new Object();
        ledger.register(completeOwner,otherSource);ledger.observed(otherSource);
        assertTrue(ledger.complete(completeOwner));
        long revision=ledger.revision();
        assertFalse(ledger.register(completeOwner,source));
        assertFalse(ledger.complete(completeOwner));
        assertTrue(ledger.revision()>revision,"A newly rejected owner must invalidate a cached completeness proof");
    }
}
