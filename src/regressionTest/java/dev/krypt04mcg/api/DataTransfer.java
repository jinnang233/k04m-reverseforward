package dev.krypt04mcg.api;
import java.util.*; import java.util.function.*;
public class DataTransfer {
 public UUID transferId() { return UUID.randomUUID(); }
 public void whenComplete(Consumer<Result> c) {}
 public record Result(UUID transferId, Status status) {}
 public enum Status { DELIVERED }
}
