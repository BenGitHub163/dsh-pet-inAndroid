package com.dshpet.android.pet

/**
 * 多开桌宠的 9 个服务载体（**只有类声明，没有逻辑**）。
 *
 * 为什么必须有 9 个类：Android 里**一个 Service 组件 = 一个实例**，
 * `startService` 永远返回同一个对象，没法"动态 new 一个 Service"。
 * 所以要同时显示 N 个桌宠，就必须在 manifest 里声明 N 个独立的 service 组件，
 * 每个组件对应一个类；这些类唯一要做的事就是把 [PetOverlayService.defaultInstanceId]
 * 换成自己的编号，其余逻辑全部继承自 [PetOverlayService]。
 *
 * 之前这里是 9 个独立文件（`PetOverlayService1.kt` … `PetOverlayService9.kt`），
 * 每个文件 6 行、内容除编号外完全相同 —— 合并成一个文件，只减少文件数，
 * **类名/包名/manifest 全部不变**，运行时行为零变化。
 *
 * 想彻底摆脱这 9 个空壳子，只能改成"一个 Service 管 N 个 PetInstance"的容器式架构
 * （见 REFACTOR 计划里的方案 B），那是另一个量级的重构，不在本次范围内。
 */
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
