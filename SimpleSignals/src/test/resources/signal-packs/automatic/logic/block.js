function evaluate(input, output) {
  output.setSpeedLimit(input.self.occupied ? 0 : 25);
  output.setModelVariant(input.self.occupied ? "stop" : "proceed");
}
