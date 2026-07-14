/** Global API injected into each train model behavior.js context. */

type MineRailsOperatingEnd = "head" | "tail";
type MineRailsBehaviorArgument = string | number | boolean | null;
type MineRailsBehaviorResult = void | boolean | string | number | null;
type MineRailsBehaviorHandler = () => MineRailsBehaviorResult;

interface MineRailsBehaviorActionOptions {
  displayName?: string;
  "display-name"?: string;
  executable?: boolean;
  requiresControl?: boolean;
  "requires-control"?: boolean;
}

/** Execute a declarative action from actions.yml. */
declare function action(name: string): boolean;

/** Play a sound at the train. Volume and pitch default to 1.0. */
declare function sound(key: string, volume?: number, pitch?: number): boolean;

/** Write a message to the MineRails plugin log. */
declare function log(message: unknown): void;

/** Return the currently active control end. */
declare function operatingEnd(): MineRailsOperatingEnd;

/** Register an action exposed by behavior.js. */
declare function defineAction(name: string, handler: MineRailsBehaviorHandler): void;
declare function defineAction(name: string, displayName: string, handler: MineRailsBehaviorHandler): void;
declare function defineAction(
  name: string,
  options: MineRailsBehaviorActionOptions,
  handler: MineRailsBehaviorHandler,
): void;
