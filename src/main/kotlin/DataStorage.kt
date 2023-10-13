import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

public class StoragePropertyDelegate<T>(
    private val storage: MutableMap<String, Any?>,
    private val initialValue: () -> T) : ReadWriteProperty<Nothing?, T> {

    override operator fun getValue(thisRef: Nothing?, property: KProperty<*>): T {
        if (!storage.containsKey(property.name)) {
            storage[property.name] = initialValue.invoke()
        }
        @Suppress("UNCHECKED_CAST")
        return storage[property.name] as T
    }

    override operator fun setValue(thisRef: Nothing?, property: KProperty<*>, value: T) {
        storage[property.name] = value
    }
}

public open class DataStorage {
    private val internalDataStorage: MutableMap<String, Any?> = mutableMapOf()

    /**
     * Create a persistent property by means of delegation, with an initial value
     * calculated by initialValue
     */
    public fun <T> storedProperty(initialValue: () -> T): StoragePropertyDelegate<T> =
        StoragePropertyDelegate(this.internalDataStorage, initialValue)

    /**
     * Clear all stored properties
     */
    public fun clearStoredProperties(): Unit =
        internalDataStorage.clear()
}

