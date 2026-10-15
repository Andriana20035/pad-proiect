package md.lab2.store;

import md.lab2.rpc.v1.AckStage;

public record AckOutcome(boolean completed, AckStage storedStage) {
}
