function evaluate(input, output) {
  const occupied = input.self.occupied;
  output.setSpeedLimit(occupied ? 0 : 25);
  output.setModelVariant(occupied ? "stop" : "proceed");
}
