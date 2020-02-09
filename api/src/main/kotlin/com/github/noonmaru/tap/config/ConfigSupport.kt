/*
 * Copyright (c) 2020 Noonmaru
 *
 * Licensed under the General Public License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/gpl-2.0.php
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.github.noonmaru.tap.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.lang.reflect.Field


@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Config(val value: String = "", val required: Boolean = true)

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class RangeInt(val min: Int = Int.MIN_VALUE, val max: Int = Int.MAX_VALUE)

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class RangeLong(val min: Long = Long.MIN_VALUE, val max: Long = Long.MAX_VALUE)

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class RangeFloat(val min: Float = java.lang.Float.MAX_VALUE, val max: Float = java.lang.Float.MIN_VALUE)

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class RangeDouble(val min: Double = java.lang.Double.MAX_VALUE, val max: Double = java.lang.Double.MIN_VALUE)

private object PrimitiveSupport {

    private val primitiveAdapters = HashMap<Class<*>, (Field, Any) -> Any>()

    init {
        primitiveAdapters[Int::class.java] = { field, value ->
            value as Number
            var i = value.toInt()
            val range = field.getAnnotation(RangeInt::class.java)
            if (range != null) {
                i = i.coerceIn(range.min, range.max)
            }
            i
        }

        primitiveAdapters[Long::class.java] = { field, value ->
            value as Number
            var l = value.toLong()
            val range = field.getAnnotation(RangeLong::class.java)
            if (range != null) {
                l = l.coerceIn(range.min, range.max)
            }
            l
        }

        primitiveAdapters[Float::class.java] = { field, value ->
            value as Number
            var f = value.toFloat()
            val range = field.getAnnotation(RangeFloat::class.java)
            if (range != null) {
                f = f.coerceIn(range.min, range.max)
            }
            f
        }

        primitiveAdapters[Double::class.java] = { field, value ->
            value as Number
            var d = value.toDouble()
            val range = field.getAnnotation(RangeDouble::class.java)
            if (range != null) {
                d = d.coerceIn(range.min, range.max)
            }
            d
        }
    }

    fun findAdapter(type: Class<*>): ((Field, Any) -> Any)? {
        return primitiveAdapters[type]
    }
}

/**
 * 설정을 인스턴스의 [Config] 속성에 적용합니다.
 * 결손된 값이 있다면 설정에 저장하고 **true**를 반환합니다.
 *
 * @param config 적용할 설정
 * @param separateByClass 클래스별 섹션 분리 여부
 *
 * @return 결손된 값이 있을 경우 **true**, 모든 값이 로딩됐을 경우 **false**
 *
 * @see Config
 * @see RangeInt
 * @see RangeLong
 * @see RangeFloat
 * @see RangeDouble
 */
fun Any.applyConfig(config: ConfigurationSection, separateByClass: Boolean = false): Boolean {
    var absent = false
    val configurables = javaClass.getConfigurables()

    for ((clazz, list) in configurables) {
        val sectionPath = if (separateByClass) clazz.simpleName.toConfigKey() else ""
        var section = if (sectionPath.isNotBlank()) config.getConfigurationSection(sectionPath) else config

        for ((field, settings) in list) {
            val key = settings.value.let { if (it.isNotBlank()) it else field.name.toConfigKey() }
            var value = section?.get(key)

            println("$key $value $section")
            println("------------")

            if (value != null) {
                val type = field.type
                if (type.isPrimitive) {
                    value = PrimitiveSupport.findAdapter(type)?.invoke(field, value)
                } else if (type.isEnum) {
                    try {
                        value = EnumSupport.valueOf(type, value.toString())
                    } catch (e: IllegalArgumentException) {
                        println("Not found Enum $type for $value")
                        e.printStackTrace()
                    }
                }

                value?.let {
                    try {
                        field.set(this, it)
                    } catch (e: Exception) {
                        println("Type mismatch! ${type.name} != ${it.javaClass.name}")
                        e.printStackTrace()
                    }
                }
                continue
            }

            value = field.get(this)

            if (!settings.required && (value == null || (value is Number && value.isZero()))) { //필요하지 않을경우 스킵
                continue
            }

            if (value.javaClass.isEnum) {
                value = EnumSupport.name(value)
            }

            absent = true

            if (section == null) section = config.createSection(sectionPath)

            section.set(key, value)
        }
    }

    return absent
}

private fun Number.isZero(): Boolean {
    return when (this) {
        is Int -> toInt() == 0
        is Long -> toLong() == 0L
        is Float -> toFloat() == 0.0F
        else -> toDouble() == 0.0
    }
}

/**
 * 파일로부터 불러온 설정을 인스턴스의 [Config] 속성에 적용합니다.
 * 결손된 값이 있다면 설정에 저장하고 **true**를 반환합니다.
 *

 * @param configFile 적용할 설정파일
 * @param separateByClass 클래스별 섹션 분리 여부
 *
 * @return 결손된 값이 있을 경우 (파일이 수정됨) **true**, 모든 값이 로딩됐을 경우 **false**
 *
 * @see Config
 * @see RangeInt
 * @see RangeLong
 * @see RangeFloat
 * @see RangeDouble
 */
fun Any.applyConfig(configFile: File, separateByClass: Boolean = false): Boolean {
    if (!configFile.exists()) {
        val config = YamlConfiguration()
        applyConfig(config)
        config.save(configFile)

        return true
    }

    val config = YamlConfiguration.loadConfiguration(configFile)

    if (applyConfig(config, separateByClass)) {
        config.save(configFile)

        return true
    }

    return false
}

private fun Class<*>.getConfigurables(): Map<Class<*>, List<Pair<Field, Config>>> {

    val superClasses = getSuperClasses(Any::class.java).reversed()
    val list: MutableMap<Class<*>, List<Pair<Field, Config>>> = LinkedHashMap()

    for (clazz in superClasses) {
        val configFields = ArrayList<Pair<Field, Config>>()

        for (field in clazz.declaredFields) {
            field.getAnnotation(Config::class.java)?.let { config ->
                field.isAccessible = true

                configFields += Pair(field, config)
            }
        }

        list += Pair(clazz, configFields)
    }

    return list
}

private fun Class<*>.getSuperClasses(limit: Class<*>): List<Class<*>> {
    val list = ArrayList<Class<*>>()

    var c: Class<*> = this

    while (c != limit) {
        list += c
        c = c.superclass
    }

    return list
}

private fun String.toConfigKey(): String {
    val builder = StringBuilder(this)

    var i = 0

    while (i < builder.count()) {
        val c = builder[i]

        if (c.isUpperCase()) {
            builder[i] = c.toLowerCase()

            if (i > 0) {
                builder.insert(i, '-')
                i++
            }
        }

        i++
    }

    return builder.toString()
}