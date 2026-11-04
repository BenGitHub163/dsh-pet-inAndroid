package com.dshpet.android.pet

/**
 * 多开槽位。Android 一个 Service 组件只有一个实例，所以 N 只桌宠必须对应 N 个
 * manifest 里声明的 Service。空壳子类只改 [PetOverlayService.defaultInstanceId]。
 *
 * 查找用 [OVERLAY_SLOT_CLASSES] 下标，不要再写 when(id)。
 * 真正「一个 Service 管 N 个窗口」要拆窗口/引擎状态，改动面太大，槽位方案对旧 ROM 兼容最好。
 */
internal val OVERLAY_SLOT_CLASSES: Array<Class<out PetOverlayService>> = arrayOf(
    PetOverlayService::class.java,
    PetOverlayService1::class.java,
    PetOverlayService2::class.java,
    PetOverlayService3::class.java,
    PetOverlayService4::class.java,
    PetOverlayService5::class.java,
    PetOverlayService6::class.java,
    PetOverlayService7::class.java,
    PetOverlayService8::class.java,
    PetOverlayService9::class.java,
)

class PetOverlayService1 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 1
}

class PetOverlayService2 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 2
}

class PetOverlayService3 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 3
}

class PetOverlayService4 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 4
}

class PetOverlayService5 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 5
}

class PetOverlayService6 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 6
}

class PetOverlayService7 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 7
}

class PetOverlayService8 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 8
}

class PetOverlayService9 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 9
}
