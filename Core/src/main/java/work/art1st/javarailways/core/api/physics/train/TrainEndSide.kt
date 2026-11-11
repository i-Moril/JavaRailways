package work.art1st.javarailways.core.api.physics.train

import org.jetbrains.annotations.ApiStatus

/**
 * Stable consist end: [A] borders the first stored car, [B] the last.
 * Operating reversal does not rename these ends. A reverse-installed edge car can expose its
 * physical B coupler at consist A (and vice versa); inspect that car's model, not the enum name.
 * This value has no thread ownership, lifetime or persistence registration.
 */
@ApiStatus.Experimental
enum class TrainEndSide { A, B }
