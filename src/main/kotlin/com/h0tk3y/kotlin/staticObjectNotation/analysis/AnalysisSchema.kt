package com.h0tk3y.kotlin.staticObjectNotation.analysis

import kotlinx.serialization.Serializable

@Serializable
data class AnalysisSchema(
    val topLevelReceiverType: DataType.DataClass,
    val dataClassesByFqName: Map<FqName, DataType.DataClass>,
    val externalFunctionsByFqName: Map<FqName, DataTopLevelFunction>,
    val externalObjectsByFqName: Map<FqName, ExternalObjectProviderKey>,
    val defaultImports: Set<FqName>
)

@Serializable
sealed interface DataType {
    @Serializable
    sealed interface ConstantType<JvmType> : DataType
    @Serializable
    data object IntDataType : ConstantType<Int> {
        override fun toString(): String = "Int"
    }
    @Serializable
    data object LongDataType : ConstantType<Long> {
        override fun toString(): String = "Long"
    }
    @Serializable
    data object StringDataType : ConstantType<String> {
        override fun toString(): String = "String"
    }
    @Serializable
    data object BooleanDataType : ConstantType<Boolean> {
        override fun toString(): String = "Boolean"
    }

    // TODO: implement nulls?
    @Serializable
    data object NullType : DataType

    @Serializable
    data object UnitType : DataType

    // TODO: `Any` type?
    // TODO: Support subtyping of some sort in the schema rather than via reflection?

    @Serializable
    data class DataClass(
        val name: FqName,
        val supertypes: Set<FqName>,
        val properties: List<DataProperty>,
        val memberFunctions: List<SchemaMemberFunction>,
        val constructors: List<DataConstructor>
    ) : DataType {
        override fun toString(): String = name.simpleName
    }
}

@Serializable
data class DataProperty(
    val name: String,
    val type: DataTypeRef,
    val isReadOnly: Boolean,
    val hasDefaultValue: Boolean,
    val isHiddenInDsl: Boolean = false,
    val isDirectAccessOnly: Boolean = false
)

@Serializable
sealed interface SchemaFunction {
    val simpleName: String
    val semantics: FunctionSemantics
    val parameters: List<DataParameter>
    val returnValueType: DataTypeRef get() = semantics.returnValueType
}

@Serializable
sealed interface SchemaMemberFunction : SchemaFunction {
    override val simpleName: String
    val receiver: DataTypeRef
    val isDirectAccessOnly: Boolean
}

@Serializable
data class DataBuilderFunction(
    override val receiver: DataTypeRef,
    override val simpleName: String,
    override val isDirectAccessOnly: Boolean,
    val dataParameter: DataParameter,
) : SchemaMemberFunction {
    override val semantics: FunctionSemantics.Builder = FunctionSemantics.Builder(receiver)
    override val parameters: List<DataParameter>
        get() = listOf(dataParameter)
}

@Serializable
data class DataTopLevelFunction(
    val packageName: String,
    override val simpleName: String,
    override val parameters: List<DataParameter>,
    override val semantics: FunctionSemantics.Pure,
) : SchemaFunction

@Serializable
data class DataMemberFunction(
    override val receiver: DataTypeRef,
    override val simpleName: String,
    override val parameters: List<DataParameter>,
    override val isDirectAccessOnly: Boolean,
    override val semantics: FunctionSemantics,
) : SchemaMemberFunction {
    init {
        if (semantics is FunctionSemantics.AccessAndConfigure) {
            if (semantics.accessor is ConfigureAccessor.Property) {
                require(semantics.accessor.receiver == receiver)
            }
        }
    }
}

@Serializable
data class DataConstructor(
    override val parameters: List<DataParameter>,
    val dataClass: DataTypeRef
) : SchemaFunction {
    override val simpleName get() = "<init>"
    override val semantics: FunctionSemantics = FunctionSemantics.Pure(dataClass)
}

@Serializable
data class DataParameter(
    val name: String?,
    val type: DataTypeRef,
    val isDefault: Boolean,
    val semantics: ParameterSemantics
)

@Serializable
sealed interface ParameterSemantics {
    @Serializable
    data class StoreValueInProperty(val dataProperty: DataProperty) : ParameterSemantics
    @Serializable
    data object Unknown : ParameterSemantics
}

@Serializable
sealed interface FunctionSemantics {
    @Serializable
    sealed interface ConfigureSemantics : FunctionSemantics {
        val configuredType: DataTypeRef
    }
    @Serializable
    sealed interface NewObjectFunctionSemantics : FunctionSemantics

    val returnValueType: DataTypeRef

    @Serializable
    class Builder(private val objectType: DataTypeRef) : FunctionSemantics {
        override val returnValueType: DataTypeRef
            get() = objectType
    }

    @Serializable
    class AccessAndConfigure(
        val accessor: ConfigureAccessor,
        val returnType: ReturnType
    ) : ConfigureSemantics {
        enum class ReturnType {
            UNIT, CONFIGURED_OBJECT
        }

        override val returnValueType: DataTypeRef
            get() = when (returnType) {
                ReturnType.UNIT -> DataType.UnitType.ref
                ReturnType.CONFIGURED_OBJECT -> accessor.objectType
            }

        override val configuredType: DataTypeRef
            get() = if (returnType == ReturnType.CONFIGURED_OBJECT) returnValueType else accessor.objectType
    }

    @Serializable
    class AddAndConfigure(
        private val objectType: DataTypeRef,
        val configureBlockRequirement: ConfigureBlockRequirement
    ) : NewObjectFunctionSemantics, ConfigureSemantics {
        override val returnValueType: DataTypeRef
            get() = objectType

        override val configuredType: DataTypeRef
            get() = returnValueType

        @Serializable
        enum class ConfigureBlockRequirement {
            NOT_ALLOWED, OPTIONAL, REQUIRED;

            val allows: Boolean get() = this != NOT_ALLOWED
            val requires: Boolean get() = this == REQUIRED
        }
    }

    @Serializable
    class Pure(override val returnValueType: DataTypeRef) : NewObjectFunctionSemantics
}

@Serializable
sealed interface ConfigureAccessor {
    val objectType: DataTypeRef

    fun access(objectOrigin: ObjectOrigin): ObjectOrigin

    @Serializable
    data class Property(val receiver: DataTypeRef, val dataProperty: DataProperty) : ConfigureAccessor {
        override val objectType: DataTypeRef
            get() = dataProperty.type

        override fun access(objectOrigin: ObjectOrigin): ObjectOrigin = ObjectOrigin.PropertyReference(objectOrigin, dataProperty, objectOrigin.originElement)
    }

    // TODO: configure all elements by addition key?
    // TODO: Do we want to support configuring external objects?
}

@Serializable
data class FqName(val packageName: String, val simpleName: String) {
    companion object {
        fun parse(fqNameString: String): FqName {
            val parts = fqNameString.split(".")
            return FqName(parts.dropLast(1).joinToString("."), parts.last())
        }
    }

    val qualifiedName get() = "$packageName.$simpleName"

    override fun toString(): String = qualifiedName
}

val DataTopLevelFunction.fqName: FqName get() = FqName(packageName, simpleName)

@Serializable
data class ExternalObjectProviderKey(val type: DataTypeRef)

@Serializable
sealed interface DataTypeRef {
    @Serializable
    data class Type(val dataType: DataType) : DataTypeRef
    @Serializable
    data class Name(val fqName: FqName) : DataTypeRef
}

val DataType.ref: DataTypeRef get() = DataTypeRef.Type(this)
