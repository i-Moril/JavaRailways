package work.art1st.mineRails.core.util

import java.lang.ref.WeakReference
import kotlin.reflect.KProperty

class WeakReferenceWrapper<T> (
    obj: T
) {
    private var ref: WeakReference<T> = WeakReference(obj)

    @Suppress("UNCHECKED_CAST")
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return ref.get() as T
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        ref = WeakReference(value)
    }
}