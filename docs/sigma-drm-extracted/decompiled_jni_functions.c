// Decompiled from libdrmpacker.so using Ghidra


// ========== Java_com_sigma_packer_SigmaDrmPacker_requestInfo @ 001754b8 ==========

undefined8
Java_com_sigma_packer_SigmaDrmPacker_requestInfo
          (long *param_1,undefined8 param_2,undefined8 param_3)

{
  undefined1 *puVar1;
  void *pvVar2;
  long lVar3;
  int iVar4;
  undefined8 uVar5;
  undefined8 uVar6;
  undefined8 uVar7;
  long *plVar8;
  undefined8 uVar9;
  byte local_78 [16];
  void *local_68;
  byte local_60;
  undefined1 auStack_5f [15];
  undefined1 *local_50;
  long local_48;
  
  lVar3 = tpidr_el0;
  local_48 = *(long *)(lVar3 + 0x28);
  uVar5 = (**(code **)(*param_1 + 0x30))(param_1,"com/sigma/packer/RequestInfo");
  uVar6 = (**(code **)(*param_1 + 0x108))
                    (param_1,uVar5,"<init>","(Ljava/lang/String;Ljava/lang/String;)V");
  iVar4 = (**(code **)(*param_1 + 0x558))(param_1,param_3);
  uVar7 = (**(code **)(*param_1 + 0x5c0))(param_1,param_3,0);
  plVar8 = (long *)FUN_0016cd00(uVar7,(long)iVar4);
  FUN_001797f0(local_78);
                    /* try { // try from 00175574 to 0017557b has its CatchHandler @ 00175638 */
  (**(code **)(*plVar8 + 0x18))(plVar8);
  puVar1 = auStack_5f;
  if ((local_60 & 1) != 0) {
    puVar1 = local_50;
  }
                    /* try { // try from 00175598 to 001755e3 has its CatchHandler @ 0017563c */
  uVar7 = (**(code **)(*param_1 + 0x538))(param_1,puVar1);
  pvVar2 = (void *)((ulong)local_78 | 1);
  if ((local_78[0] & 1) != 0) {
    pvVar2 = local_68;
  }
  uVar9 = (**(code **)(*param_1 + 0x538))(param_1,pvVar2);
  uVar5 = FUN_00175650(param_1,uVar5,uVar6,uVar7,uVar9);
  if ((local_60 & 1) != 0) {
    operator_delete(local_50);
  }
  if ((local_78[0] & 1) != 0) {
    operator_delete(local_68);
  }
  if (*(long *)(lVar3 + 0x28) == local_48) {
    return uVar5;
  }
                    /* WARNING: Subroutine does not return */
  __stack_chk_fail();
}



// ========== Java_com_sigma_packer_SigmaDrmPacker_getKeyRequest @ 00175730 ==========

/* WARNING: Type propagation algorithm not settling */
/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined8
Java_com_sigma_packer_SigmaDrmPacker_getKeyRequest
          (long *param_1,undefined8 param_2,undefined8 param_3,undefined8 param_4,undefined8 param_5
          ,undefined8 param_6,undefined4 param_7,undefined8 param_8)

{
  void *pvVar1;
  void *pvVar2;
  long lVar3;
  undefined8 uVar4;
  undefined8 uVar5;
  undefined8 uVar6;
  undefined8 uVar7;
  undefined8 uVar8;
  char cVar9;
  int iVar10;
  undefined8 *puVar11;
  undefined8 uVar12;
  undefined8 uVar13;
  long lVar14;
  long *plVar15;
  ulong local_d0;
  undefined8 uStack_c8;
  undefined8 *local_c0;
  byte local_b8 [16];
  void *local_a8;
  byte local_a0;
  undefined7 uStack_9f;
  char cStack_98;
  undefined7 uStack_97;
  char local_90;
  undefined4 uStack_8f;
  undefined1 uStack_8b;
  undefined2 uStack_8a;
  byte local_80 [16];
  void *local_70;
  long local_68;
  
  lVar3 = tpidr_el0;
  local_68 = *(long *)(lVar3 + 0x28);
  puVar11 = operator_new(0x30);
  local_90 = (char)puVar11;
  uStack_8f = (undefined4)((ulong)puVar11 >> 8);
  uStack_8b = (undefined1)((ulong)puVar11 >> 0x28);
  uStack_8a = (undefined2)((ulong)puVar11 >> 0x30);
  *(undefined1 *)(puVar11 + 4) = 0;
  uVar4 = s_YDt8oX564bau5g___0014b03b._8_8_;
  uVar13 = s_YDt8oX564bau5g___0014b03b._0_8_;
  uVar12 = _DAT_0014b02b;
  cStack_98 = (char)_UNK_0014d818;
  uStack_97 = (undefined7)((ulong)_UNK_0014d818 >> 8);
  local_a0 = (byte)_DAT_0014d810;
  uStack_9f = (undefined7)((ulong)_DAT_0014d810 >> 8);
  puVar11[1] = _UNK_0014b033;
  *puVar11 = uVar12;
  puVar11[3] = uVar4;
  puVar11[2] = uVar13;
                    /* try { // try from 001757a4 to 001757b3 has its CatchHandler @ 00175c14 */
  FUN_0017a4b8(local_80,&local_a0);
  pvVar2 = (void *)((ulong)local_80 | 1);
  if ((local_80[0] & 1) != 0) {
    pvVar2 = local_70;
  }
                    /* try { // try from 001757d0 to 001757d7 has its CatchHandler @ 00175bfc */
  uVar12 = (**(code **)(*param_1 + 0x30))(param_1,pvVar2);
  if ((local_80[0] & 1) != 0) {
    operator_delete(local_70);
  }
  if ((local_a0 & 1) != 0) {
    operator_delete((void *)CONCAT26(uStack_8a,CONCAT15(uStack_8b,CONCAT41(uStack_8f,local_90))));
  }
  cVar9 = (**(code **)(*param_1 + 0x720))(param_1);
  if (cVar9 == '\0') {
    puVar11 = operator_new(0x30);
    local_90 = (char)puVar11;
    uStack_8f = (undefined4)((ulong)puVar11 >> 8);
    uStack_8b = (undefined1)((ulong)puVar11 >> 0x28);
    uStack_8a = (undefined2)((ulong)puVar11 >> 0x30);
    *(undefined1 *)((long)puVar11 + 0x2c) = 0;
    uVar7 = s_5gR_XBdIQ_PynVaZ_0014b94e._8_8_;
    uVar6 = _DAT_0014b942;
    uVar5 = _DAT_0014b932;
    uVar13 = CONCAT44(s_5gR_XBdIQ_PynVaZ_0014b94e._0_4_,_UNK_0014b94a);
    cStack_98 = (char)_UNK_0014d798;
    uStack_97 = (undefined7)((ulong)_UNK_0014d798 >> 8);
    local_a0 = (byte)_DAT_0014d790;
    uStack_9f = (undefined7)((ulong)_DAT_0014d790 >> 8);
    uVar4 = CONCAT44(s_5gR_XBdIQ_PynVaZ_0014b94e._4_4_,s_5gR_XBdIQ_PynVaZ_0014b94e._0_4_);
    puVar11[1] = _UNK_0014b93a;
    *puVar11 = uVar5;
    puVar11[3] = uVar13;
    puVar11[2] = uVar6;
    *(undefined8 *)((long)puVar11 + 0x24) = uVar7;
    *(undefined8 *)((long)puVar11 + 0x1c) = uVar4;
                    /* try { // try from 00175848 to 00175857 has its CatchHandler @ 00175bf8 */
    FUN_0017a4b8(local_80,&local_a0);
    pvVar2 = (void *)((ulong)local_80 | 1);
    if ((local_80[0] & 1) != 0) {
      pvVar2 = local_70;
    }
                    /* try { // try from 00175874 to 0017587b has its CatchHandler @ 00175bf4 */
    uVar13 = (**(code **)(*param_1 + 0x30))(param_1,pvVar2);
    if ((local_80[0] & 1) != 0) {
      operator_delete(local_70);
    }
    if ((local_a0 & 1) != 0) {
      operator_delete((void *)CONCAT26(uStack_8a,CONCAT15(uStack_8b,CONCAT41(uStack_8f,local_90))));
    }
    cVar9 = (**(code **)(*param_1 + 0x720))(param_1);
    if (cVar9 != '\0') goto LAB_00175acc;
    uStack_8b = 0;
    local_a0 = 0x28;
    uStack_8f = 0x3d3d4149;
    uStack_97 = (undefined7)s_3NRaAg1RxZGn4IiVIA___0014bc79._8_8_;
    local_90 = SUB81(s_3NRaAg1RxZGn4IiVIA___0014bc79._8_8_,7);
    uStack_9f = (undefined7)s_3NRaAg1RxZGn4IiVIA___0014bc79._0_8_;
    cStack_98 = SUB81(s_3NRaAg1RxZGn4IiVIA___0014bc79._0_8_,7);
                    /* try { // try from 001758e4 to 001758f3 has its CatchHandler @ 00175bf0 */
    FUN_0017a4b8(local_80,&local_a0);
    pvVar2 = (void *)((ulong)local_80 | 1);
    if ((local_80[0] & 1) != 0) {
      pvVar2 = local_70;
    }
                    /* try { // try from 00175908 to 0017590f has its CatchHandler @ 00175be0 */
    local_c0 = operator_new(0x70);
    *(undefined1 *)((long)local_c0 + 0x6c) = 0;
    uVar6 = _DAT_00149844;
    uVar5 = _DAT_00149834;
    uVar4 = CONCAT44(s_JZr525jg61lZaA___00149850._0_4_,_UNK_0014984c);
    uStack_c8 = _UNK_0014d888;
    local_d0 = _DAT_0014d880;
    local_c0[9] = _UNK_0014983c;
    local_c0[8] = uVar5;
    local_c0[0xb] = uVar4;
    local_c0[10] = uVar6;
    uVar8 = _UNK_0014980c;
    uVar7 = _DAT_00149804;
    uVar6 = _UNK_001497fc;
    uVar5 = _DAT_001497f4;
    uVar4 = CONCAT44(s_JZr525jg61lZaA___00149850._4_4_,s_JZr525jg61lZaA___00149850._0_4_);
    *(undefined8 *)((long)local_c0 + 100) = s_JZr525jg61lZaA___00149850._8_8_;
    *(undefined8 *)((long)local_c0 + 0x5c) = uVar4;
    local_c0[1] = uVar6;
    *local_c0 = uVar5;
    local_c0[3] = uVar8;
    local_c0[2] = uVar7;
    uVar6 = _UNK_0014982c;
    uVar5 = _DAT_00149824;
    uVar4 = _DAT_00149814;
    local_c0[5] = _UNK_0014981c;
    local_c0[4] = uVar4;
    local_c0[7] = uVar6;
    local_c0[6] = uVar5;
                    /* try { // try from 0017594c to 0017595b has its CatchHandler @ 00175bc8 */
    FUN_0017a4b8(local_b8,&local_d0);
    pvVar1 = (void *)((ulong)local_b8 | 1);
    if ((local_b8[0] & 1) != 0) {
      pvVar1 = local_a8;
    }
                    /* try { // try from 00175978 to 00175987 has its CatchHandler @ 00175bb0 */
    uVar12 = (**(code **)(*param_1 + 0x108))(param_1,uVar12,pvVar2,pvVar1);
    if ((local_b8[0] & 1) != 0) {
      operator_delete(local_a8);
    }
    if ((local_d0 & 1) != 0) {
      operator_delete(local_c0);
    }
    if ((local_80[0] & 1) != 0) {
      operator_delete(local_70);
    }
    if ((local_a0 & 1) != 0) {
      operator_delete((void *)CONCAT26(uStack_8a,CONCAT15(uStack_8b,CONCAT41(uStack_8f,local_90))));
    }
    cVar9 = (**(code **)(*param_1 + 0x720))(param_1);
    if (cVar9 != '\0') goto LAB_00175acc;
    uVar12 = FUN_00175c30(param_1,param_3,uVar12,param_4,param_5,param_6,param_7,param_8);
    cVar9 = (**(code **)(*param_1 + 0x720))(param_1);
    if (cVar9 != '\0') goto LAB_00175acc;
    FUN_0016f320(&local_a0,"3NRaDQZX8A==");
                    /* try { // try from 00175a20 to 00175a2f has its CatchHandler @ 00175bac */
    FUN_0017a4b8(local_80,&local_a0);
    pvVar2 = (void *)((ulong)local_80 | 1);
    if ((local_80[0] & 1) != 0) {
      pvVar2 = local_70;
    }
                    /* try { // try from 00175a4c to 00175a5f has its CatchHandler @ 00175ba8 */
    uVar13 = (**(code **)(*param_1 + 0x108))(param_1,uVar13,pvVar2,&DAT_00149f1a);
    if ((local_80[0] & 1) != 0) {
      operator_delete(local_70);
    }
    if ((local_a0 & 1) != 0) {
      operator_delete((void *)CONCAT26(uStack_8a,CONCAT15(uStack_8b,CONCAT41(uStack_8f,local_90))));
    }
    cVar9 = (**(code **)(*param_1 + 0x720))(param_1);
    if (cVar9 != '\0') goto LAB_00175acc;
    lVar14 = FUN_00175c30(param_1,uVar12,uVar13,0);
    cVar9 = (**(code **)(*param_1 + 0x720))(param_1);
    if (cVar9 != '\0') goto LAB_00175acc;
    if (lVar14 != 0) {
      uVar13 = (**(code **)(*param_1 + 0x5c0))(param_1,lVar14,0);
      iVar10 = (**(code **)(*param_1 + 0x558))(param_1,lVar14);
      plVar15 = (long *)FUN_0016cd00(uVar13,(long)iVar10);
      FUN_001795b4();
      (**(code **)(*plVar15 + 0x18))(plVar15);
      goto LAB_00175ae0;
    }
  }
  else {
LAB_00175acc:
    (**(code **)(*param_1 + 0x88))(param_1);
  }
  uVar12 = 0;
LAB_00175ae0:
  if (*(long *)(lVar3 + 0x28) != local_68) {
                    /* WARNING: Subroutine does not return */
    __stack_chk_fail();
  }
  return uVar12;
}



// ========== Java_com_sigma_packer_SigmaDrmPacker_provideKeyResponse @ 00175ccc ==========

/* WARNING: Type propagation algorithm not settling */
/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined8
Java_com_sigma_packer_SigmaDrmPacker_provideKeyResponse
          (long *param_1,undefined8 param_2,undefined8 param_3,undefined8 param_4,undefined8 param_5
          )

{
  void *pvVar1;
  void *pvVar2;
  long lVar3;
  byte bVar4;
  char cVar5;
  int iVar6;
  undefined4 uVar7;
  undefined8 uVar8;
  undefined8 uVar9;
  undefined8 uVar10;
  undefined8 uVar11;
  byte local_d0;
  char local_cf [8];
  undefined4 local_c7;
  undefined1 local_c3;
  void *local_c0;
  byte local_b8 [16];
  void *local_a8;
  ulong local_a0;
  undefined8 uStack_98;
  undefined8 *local_90;
  byte local_80 [16];
  void *local_70;
  long local_68;
  
  lVar3 = tpidr_el0;
  local_68 = *(long *)(lVar3 + 0x28);
  uVar8 = (**(code **)(*param_1 + 0x5c0))(param_1,param_5,0);
  iVar6 = (**(code **)(*param_1 + 0x558))(param_1,param_5);
  FUN_0016cd00(uVar8,(long)iVar6);
  uVar9 = FUN_00178f60();
  local_90 = operator_new(0x30);
  *(undefined1 *)(local_90 + 4) = 0;
  uVar11 = s_YDt8oX564bau5g___0014b03b._8_8_;
  uVar10 = s_YDt8oX564bau5g___0014b03b._0_8_;
  uVar8 = _DAT_0014b02b;
  uStack_98 = _UNK_0014d818;
  local_a0 = _DAT_0014d810;
  local_90[1] = _UNK_0014b033;
  *local_90 = uVar8;
  local_90[3] = uVar11;
  local_90[2] = uVar10;
                    /* try { // try from 00175d74 to 00175d83 has its CatchHandler @ 00176050 */
  FUN_0017a4b8(local_80,&local_a0);
  pvVar1 = (void *)((ulong)local_80 | 1);
  if ((local_80[0] & 1) != 0) {
    pvVar1 = local_70;
  }
                    /* try { // try from 00175da0 to 00175da7 has its CatchHandler @ 00176038 */
  uVar8 = (**(code **)(*param_1 + 0x30))(param_1,pvVar1);
  if ((local_80[0] & 1) != 0) {
    operator_delete(local_70);
  }
  if ((local_a0 & 1) != 0) {
    operator_delete(local_90);
  }
  cVar5 = (**(code **)(*param_1 + 0x720))(param_1);
  if (cVar5 == '\0') {
    local_90 = operator_new(0x20);
    *(undefined1 *)(local_90 + 3) = 0;
    uVar11 = s_MDRRh09H_0014b4ee._0_8_;
    uVar10 = _DAT_0014b4de;
    uStack_98 = _UNK_0014d7b8;
    local_a0 = _DAT_0014d7b0;
    local_90[1] = _UNK_0014b4e6;
    *local_90 = uVar10;
    local_90[2] = uVar11;
                    /* try { // try from 00175e18 to 00175e23 has its CatchHandler @ 00176034 */
    FUN_0017a4b8(local_80,&local_a0);
    pvVar2 = local_70;
    bVar4 = local_80[0];
    local_c3 = 0;
    local_d0 = 0x18;
    local_c7 = 0x3d384a37;
    local_cf[0] = s_k6UdVX1x7J8__0014a0f1[0];
    local_cf[1] = s_k6UdVX1x7J8__0014a0f1[1];
    local_cf[2] = s_k6UdVX1x7J8__0014a0f1[2];
    local_cf[3] = s_k6UdVX1x7J8__0014a0f1[3];
    local_cf[4] = s_k6UdVX1x7J8__0014a0f1[4];
    local_cf[5] = s_k6UdVX1x7J8__0014a0f1[5];
    local_cf[6] = s_k6UdVX1x7J8__0014a0f1[6];
    local_cf[7] = s_k6UdVX1x7J8__0014a0f1[7];
                    /* try { // try from 00175e54 to 00175e63 has its CatchHandler @ 00176014 */
    FUN_0017a4b8(local_b8,&local_d0);
    pvVar1 = (void *)((ulong)local_80 | 1);
    if ((bVar4 & 1) != 0) {
      pvVar1 = pvVar2;
    }
    pvVar2 = (void *)((ulong)local_b8 | 1);
    if ((local_b8[0] & 1) != 0) {
      pvVar2 = local_a8;
    }
                    /* try { // try from 00175e90 to 00175e9b has its CatchHandler @ 00175fe0 */
    uVar8 = (**(code **)(*param_1 + 0x108))(param_1,uVar8,pvVar1,pvVar2);
    if ((local_b8[0] & 1) != 0) {
      operator_delete(local_a8);
    }
    if ((local_d0 & 1) != 0) {
      operator_delete(local_c0);
    }
    if ((local_80[0] & 1) != 0) {
      operator_delete(local_70);
    }
    if ((local_a0 & 1) != 0) {
      operator_delete(local_90);
    }
    cVar5 = (**(code **)(*param_1 + 0x720))(param_1);
    if (cVar5 == '\0') {
      uVar7 = FUN_0016d580(uVar9);
      uVar10 = (**(code **)(*param_1 + 0x580))(param_1,uVar7);
      uVar11 = FUN_0016d578(uVar9);
      (**(code **)(*param_1 + 0x680))(param_1,uVar10,0,uVar7,uVar11);
      uVar8 = FUN_00175c30(param_1,param_3,uVar8,param_4,uVar10);
      (**(code **)(*param_1 + 0xb8))(param_1,uVar10);
      goto LAB_00175ef4;
    }
  }
  (**(code **)(*param_1 + 0x88))(param_1);
  uVar8 = 0;
LAB_00175ef4:
  if (*(long *)(lVar3 + 0x28) != local_68) {
                    /* WARNING: Subroutine does not return */
    __stack_chk_fail();
  }
  return uVar8;
}



// ========== JNI_OnLoad @ 0017606c ==========

undefined8 JNI_OnLoad(undefined8 param_1)

{
  undefined8 uVar1;
  
  uVar1 = FUN_00176de4();
  FUN_00176e7c(uVar1,param_1);
  return 0x10006;
}



// Total JNI functions decompiled: 4
