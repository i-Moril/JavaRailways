/** Global API in each car's independent, lazy behavior.js context. State survives consist transfers. */

type JavaRailwaysOperatingEnd = "head" | "tail";
type JavaRailwaysBehaviorArgument = string | number | boolean | null;
type JavaRailwaysBehaviorResult = void | boolean | string | number | null;
type JavaRailwaysBehaviorHandler = () => JavaRailwaysBehaviorResult;

interface JavaRailwaysBehaviorActionOptions {
  displayName?: string;
  "display-name"?: string;
  executable?: boolean;
  requiresControl?: boolean;
  "requires-control"?: boolean;
}

/** Execute this car's source-package action; delayed effects keep this exact car and physical side. */
declare function action(name: string): boolean;

/** Query this car's configured model without playing effects; available during initialization. */
declare function hasAnimation(name: string, scene?: string | null): boolean;

/** Play a sound at this car. Volume and pitch default to 1.0. */
declare function sound(key: string, volume?: number, pitch?: number): boolean;

/** Write a message to the JavaRailways plugin log. */
declare function log(message: unknown): void;

/** Control end relative to this car's installation, captured for this call. Flip semantic sides once. */
declare function operatingEnd(): JavaRailwaysOperatingEnd;

/** Register an action exposed by behavior.js. */
declare function defineAction(name: string, handler: JavaRailwaysBehaviorHandler): void;
declare function defineAction(name: string, displayName: string, handler: JavaRailwaysBehaviorHandler): void;
declare function defineAction(
  name: string,
  options: JavaRailwaysBehaviorActionOptions,
  handler: JavaRailwaysBehaviorHandler,
): void;
