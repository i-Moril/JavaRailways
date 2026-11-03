export type AuthoringCommand='new-run'|'copy-run'|'add-duty'|'add-timed-operation'|'add-duty-program'|'add-formation-split'|'add-formation-couple'|'undo'|'redo'|'save'|'save-formal'|'compile'|'simulate'|'publish';
// Empty reason means available. The sole editor owner computes every command.
export type AuthoringCommandState=Record<AuthoringCommand,string>;
