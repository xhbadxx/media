// Helper functions from libdrmpacker.so


// ========== FUN_0016cd00 @ 0016cd00 ==========

undefined8 * FUN_0016cd00(undefined8 param_1,size_t param_2)

{
  undefined8 *puVar1;
  void *__s;
  
  puVar1 = operator_new(0x40);
                    /* try { // try from 0016cd24 to 0016cd27 has its CatchHandler @ 0016cd80 */
  FUN_0016dbbc();
  *(undefined1 *)(puVar1 + 7) = 0;
  puVar1[5] = param_2;
  puVar1[6] = 0;
  *puVar1 = &PTR_FUN_00201958;
  __s = malloc(param_2);
  puVar1[3] = __s;
  memset(__s,0,param_2);
  puVar1[4] = __s;
  FUN_0016cd94(puVar1,param_1,param_2);
  return puVar1;
}



// ========== FUN_001797f0 @ 001797f0 ==========

void FUN_001797f0(basic_string<char,std::__ndk1::char_traits<char>,std::__ndk1::allocator<char>>
                  *param_1,undefined8 param_2)

{
  undefined1 uVar1;
  long lVar2;
  long lVar3;
  undefined1 *puVar4;
  basic_string local_78 [16];
  void *local_68;
  basic_string local_60 [16];
  void *local_50;
  long local_48;
  
  lVar2 = tpidr_el0;
  local_48 = *(long *)(lVar2 + 0x28);
  FUN_00176de4();
  FUN_00177c1c(local_60);
                    /* try { // try from 00179828 to 0017982b has its CatchHandler @ 001798fc */
  lVar3 = FUN_00176de4();
  uVar1 = *(undefined1 *)(lVar3 + 0x128);
                    /* try { // try from 00179830 to 00179837 has its CatchHandler @ 001798f8 */
  puVar4 = operator_new(0x20);
  *puVar4 = uVar1;
                    /* try { // try from 00179844 to 0017984f has its CatchHandler @ 001798ec */
  std::__ndk1::basic_string<char,std::__ndk1::char_traits<char>,std::__ndk1::allocator<char>>::
  basic_string((basic_string<char,std::__ndk1::char_traits<char>,std::__ndk1::allocator<char>> *)
               (puVar4 + 8),local_60);
                    /* try { // try from 00179850 to 0017985f has its CatchHandler @ 001798e8 */
  FUN_00179950(local_78,puVar4,param_2);
  *(undefined8 *)(param_1 + 0x18) = 0;
  *(undefined8 *)(param_1 + 0x10) = 0;
  *(undefined8 *)(param_1 + 0x28) = 0;
  *(undefined8 *)(param_1 + 0x20) = 0;
  *(undefined8 *)(param_1 + 8) = 0;
  *(undefined8 *)param_1 = 0;
                    /* try { // try from 0017986c to 00179883 has its CatchHandler @ 00179918 */
  std::__ndk1::basic_string<char,std::__ndk1::char_traits<char>,std::__ndk1::allocator<char>>::
  operator=(param_1,local_60);
  std::__ndk1::basic_string<char,std::__ndk1::char_traits<char>,std::__ndk1::allocator<char>>::
  operator=(param_1 + 0x18,local_78);
  if (((byte)*(basic_string<char,std::__ndk1::char_traits<char>,std::__ndk1::allocator<char>> *)
              (puVar4 + 8) & 1) != 0) {
    operator_delete(*(void **)(puVar4 + 0x18));
  }
  operator_delete(puVar4);
  if (((byte)local_78[0] & 1) != 0) {
    operator_delete(local_68);
  }
  if (((byte)local_60[0] & 1) != 0) {
    operator_delete(local_50);
  }
  if (*(long *)(lVar2 + 0x28) == local_48) {
    return;
  }
                    /* WARNING: Subroutine does not return */
  __stack_chk_fail();
}



// ========== FUN_00175650 @ 00175650 ==========

void FUN_00175650(long *param_1,undefined8 param_2,undefined8 param_3,undefined8 param_4,
                 undefined8 param_5,undefined8 param_6,undefined8 param_7,undefined8 param_8)

{
  long lVar1;
  long lVar2;
  undefined1 auStack_a0 [8];
  undefined8 local_98;
  undefined8 uStack_90;
  undefined8 local_88;
  undefined8 uStack_80;
  undefined8 local_78;
  undefined1 *local_70;
  undefined1 **ppuStack_68;
  undefined1 *puStack_60;
  undefined8 uStack_58;
  
  ppuStack_68 = &local_70;
  puStack_60 = auStack_a0;
  lVar1 = tpidr_el0;
  lVar2 = *(long *)(lVar1 + 0x28);
  uStack_58 = 0xffffff80ffffffd8;
  local_98 = param_4;
  uStack_90 = param_5;
  local_88 = param_6;
  uStack_80 = param_7;
  local_78 = param_8;
  local_70 = (undefined1 *)register0x00000008;
  (**(code **)(*param_1 + 0xe8))();
  if (*(long *)(lVar1 + 0x28) == lVar2) {
    return;
  }
                    /* WARNING: Subroutine does not return */
  __stack_chk_fail();
}



// ========== FUN_0017a4b8 @ 0017a4b8 ==========

void FUN_0017a4b8(undefined8 param_1,undefined8 param_2)

{
  long *plVar1;
  long *plVar2;
  
  plVar1 = (long *)FUN_0016ce94(param_2,1);
  plVar2 = (long *)FUN_0016cb14(plVar1,&DAT_0020aaa8,0x10);
  FUN_0016d5a4(param_1,plVar2,0);
                    /* try { // try from 0017a500 to 0017a517 has its CatchHandler @ 0017a528 */
  (**(code **)(*plVar1 + 0x18))(plVar1);
  (**(code **)(*plVar2 + 0x18))(plVar2);
  return;
}



// ========== FUN_00176de4 @ 00176de4 ==========

undefined8 * FUN_00176de4(void)

{
  undefined8 *puVar1;
  
  if (DAT_0020af88 == (undefined8 *)0x0) {
    puVar1 = operator_new(0x130);
    *puVar1 = &PTR_FUN_00201b98;
                    /* try { // try from 00176e1c to 00176e23 has its CatchHandler @ 00176e68 */
    FUN_0016f2d0(puVar1 + 5);
                    /* try { // try from 00176e28 to 00176e2b has its CatchHandler @ 00176e58 */
    FUN_0016f2d0(puVar1 + 0x15);
    *(undefined1 *)(puVar1 + 0x25) = 0;
    DAT_0020af88 = puVar1;
    (**(code **)*puVar1)(puVar1);
  }
  return DAT_0020af88;
}



// ========== FUN_00176e7c @ 00176e7c ==========

void FUN_00176e7c(long param_1,long *param_2)

{
  long *plVar1;
  undefined8 uVar2;
  undefined8 uVar3;
  long lVar4;
  
  lVar4 = *param_2;
  *(long **)(param_1 + 0x18) = param_2;
  (**(code **)(lVar4 + 0x30))(param_2,param_1 + 8,0x10006);
  plVar1 = *(long **)(param_1 + 8);
  lVar4 = *plVar1;
  *(long **)(param_1 + 0x10) = plVar1;
  uVar2 = (**(code **)(lVar4 + 0x30))(plVar1,"android/app/ActivityThread");
  uVar3 = (**(code **)(**(long **)(param_1 + 0x10) + 0x388))
                    (*(long **)(param_1 + 0x10),uVar2,"currentActivityThread",
                     "()Landroid/app/ActivityThread;");
  uVar3 = FUN_00177ff0(*(undefined8 *)(param_1 + 0x10),uVar2,uVar3);
  uVar2 = (**(code **)(**(long **)(param_1 + 0x10) + 0x108))
                    (*(long **)(param_1 + 0x10),uVar2,"getApplication","()Landroid/app/Application;"
                    );
  uVar2 = FUN_00175c30(*(undefined8 *)(param_1 + 0x10),uVar3,uVar2);
  *(undefined8 *)(param_1 + 0x20) = uVar2;
  FUN_00176f60(param_1);
  FUN_0017710c(param_1);
  return;
}



// ========== FUN_0016f320 @ 0016f320 ==========

void FUN_0016f320(ulong *param_1,char *param_2)

{
  size_t __n;
  undefined1 *__dest;
  ulong uVar1;
  
  __n = strlen(param_2);
  if (0xffffffffffffffef < __n) {
                    /* WARNING: Subroutine does not return */
    std::__ndk1::__basic_string_common<true>::__throw_length_error();
  }
  if (__n < 0x17) {
    __dest = (undefined1 *)((long)param_1 + 1);
    *(char *)param_1 = (char)((int)__n << 1);
    if (__n == 0) goto LAB_0016f394;
  }
  else {
    uVar1 = __n + 0x10 & 0xfffffffffffffff0;
    __dest = operator_new(uVar1);
    param_1[1] = __n;
    param_1[2] = (ulong)__dest;
    *param_1 = uVar1 | 1;
  }
  memcpy(__dest,param_2,__n);
LAB_0016f394:
  __dest[__n] = 0;
  return;
}



// ========== FUN_00178f60 @ 00178f60 ==========

long * FUN_00178f60(long *param_1)

{
  ulong uVar1;
  void *pvVar2;
  long lVar3;
  bool bVar4;
  undefined1 *puVar5;
  void *pvVar6;
  char cVar7;
  undefined1 uVar8;
  int iVar9;
  uint uVar10;
  uint uVar11;
  undefined4 uVar12;
  long lVar13;
  void *pvVar14;
  long *plVar15;
  undefined8 uVar16;
  undefined8 uVar17;
  undefined8 uVar18;
  undefined8 uVar19;
  ulong uVar20;
  size_t sVar21;
  undefined1 *puVar22;
  void *local_190;
  void *local_188;
  void *local_180;
  void *local_178;
  undefined1 *local_170;
  undefined1 *local_168;
  undefined1 auStack_160 [224];
  undefined8 local_80;
  undefined8 uStack_78;
  long local_70;
  
  lVar3 = tpidr_el0;
  local_70 = *(long *)(lVar3 + 0x28);
  iVar9 = FUN_0016d534();
  if (iVar9 == 0x534d5756) {
    uVar10 = FUN_0016d534(param_1);
    lVar13 = FUN_0016d580(param_1);
    if ((lVar13 + 8U == (ulong)uVar10) && (uVar11 = FUN_0016d534(param_1), uVar11 < uVar10)) {
      cVar7 = FUN_0016d558(param_1);
      uVar12 = FUN_0016d558(param_1);
      uVar10 = FUN_0016d558(param_1);
      local_178 = (void *)0x0;
      local_170 = (undefined1 *)0x0;
      local_168 = (undefined1 *)0x0;
      puVar5 = (undefined1 *)0x0;
      for (uVar10 = uVar10 & 0xff; uVar10 != 0; uVar10 = uVar10 - 1) {
                    /* try { // try from 00179028 to 00179073 has its CatchHandler @ 00179380 */
        uVar8 = FUN_0016d558(param_1);
        pvVar6 = local_178;
        if (puVar5 < local_168) {
          puVar22 = puVar5 + 1;
          *puVar5 = uVar8;
          local_170 = puVar22;
        }
        else {
          sVar21 = (long)puVar5 - (long)local_178;
          uVar1 = sVar21 + 1;
          if ((long)uVar1 < 0) {
                    /* try { // try from 001792fc to 00179303 has its CatchHandler @ 0017937c */
                    /* WARNING: Subroutine does not return */
            std::__ndk1::__vector_base_common<true>::__throw_length_error();
          }
          uVar20 = ((long)local_168 - (long)local_178) * 2;
          if (uVar1 <= uVar20) {
            uVar1 = uVar20;
          }
          if (0x3ffffffffffffffe < (ulong)((long)local_168 - (long)local_178)) {
            uVar1 = 0x7fffffffffffffff;
          }
          if (uVar1 == 0) {
            pvVar14 = (void *)0x0;
          }
          else {
            pvVar14 = operator_new(uVar1);
          }
          puVar22 = (undefined1 *)((long)pvVar14 + sVar21) + 1;
          *(undefined1 *)((long)pvVar14 + sVar21) = uVar8;
          if (0 < (long)sVar21) {
            memcpy(pvVar14,pvVar6,sVar21);
          }
          local_168 = (undefined1 *)((long)pvVar14 + uVar1);
          local_178 = pvVar14;
          local_170 = puVar22;
          if (pvVar6 != (void *)0x0) {
            operator_delete(pvVar6);
          }
        }
        puVar5 = puVar22;
      }
      local_80 = 0;
      uStack_78 = 0;
                    /* try { // try from 001790c0 to 001790cf has its CatchHandler @ 00179328 */
      FUN_0016d4dc(param_1,&local_80,0x10);
                    /* try { // try from 001790d0 to 001790d7 has its CatchHandler @ 00179324 */
      uVar10 = FUN_0016d534(param_1);
      pvVar14 = malloc((ulong)uVar10);
                    /* try { // try from 001790ec to 001790fb has its CatchHandler @ 00179320 */
      FUN_0016d4dc(param_1,pvVar14,(ulong)uVar10);
      pvVar6 = local_178;
      local_190 = (void *)0x0;
      local_188 = (void *)0x0;
      local_180 = (void *)0x0;
      uVar1 = (long)puVar5 - (long)local_178;
      if (uVar1 != 0) {
        if ((long)uVar1 < 0) {
                    /* try { // try from 00179304 to 0017930b has its CatchHandler @ 00179330 */
                    /* WARNING: Subroutine does not return */
          std::__ndk1::__vector_base_common<true>::__throw_length_error();
        }
                    /* try { // try from 00179118 to 0017911f has its CatchHandler @ 00179330 */
        local_190 = operator_new(uVar1);
        pvVar2 = (void *)((long)local_190 + uVar1);
        local_188 = local_190;
        local_180 = pvVar2;
        memcpy(local_190,pvVar6,uVar1);
        local_188 = pvVar2;
      }
                    /* try { // try from 0017913c to 0017914f has its CatchHandler @ 0017931c */
      plVar15 = (long *)FUN_0017939c(&local_190,uVar12,pvVar14,uVar10);
      if (local_190 != (void *)0x0) {
        local_188 = local_190;
        operator_delete(local_190);
      }
      if (pvVar14 != (void *)0x0) {
        operator_delete(pvVar14);
      }
      if (plVar15 == (long *)0x0) {
LAB_00179298:
        bVar4 = false;
      }
      else {
        if (cVar7 == '\x02') {
                    /* try { // try from 001791d4 to 001791db has its CatchHandler @ 00179318 */
          FUN_00174b40(auStack_160);
                    /* try { // try from 001791dc to 0017923b has its CatchHandler @ 00179364 */
          sVar21 = FUN_0016d580(param_1);
          pvVar14 = malloc(sVar21);
          uVar16 = FUN_0016d578(plVar15);
          FUN_00174b48(auStack_160,uVar16);
          uVar16 = FUN_0016d578(param_1);
          uVar12 = FUN_0016d580(param_1);
          uVar17 = FUN_0016d578(plVar15);
          FUN_00174fd0(auStack_160,pvVar14,uVar16,uVar12,uVar17,&local_80);
                    /* try { // try from 0017923c to 00179243 has its CatchHandler @ 00179314 */
          lVar13 = FUN_0016d580(param_1);
          plVar15 = (long *)(ulong)*(byte *)((long)pvVar14 + lVar13 + -1);
          if ((long *)0x10 < plVar15) {
            free(pvVar14);
            FUN_00174b44(auStack_160);
            goto LAB_00179298;
          }
                    /* try { // try from 00179254 to 00179267 has its CatchHandler @ 00179310 */
          lVar13 = FUN_0016d580(param_1);
          plVar15 = (long *)FUN_0016cd00(pvVar14,lVar13 - (long)plVar15);
          free(pvVar14);
          FUN_00174b44(auStack_160);
        }
        else {
          if (cVar7 != '\x01') goto LAB_00179298;
                    /* try { // try from 0017918c to 001791cb has its CatchHandler @ 0017932c */
          uVar16 = FUN_0016d578(param_1);
          uVar17 = FUN_0016d580(param_1);
          uVar18 = FUN_0016d578(plVar15);
          uVar19 = FUN_0016d580(plVar15);
          plVar15 = (long *)FUN_0016cbcc(uVar16,uVar17,uVar18,uVar19);
        }
        bVar4 = true;
      }
      if (pvVar6 != (void *)0x0) {
        operator_delete(pvVar6);
      }
      if (bVar4) goto LAB_001792c8;
    }
  }
  FUN_0016d588(param_1);
  (**(code **)(*param_1 + 0x10))(param_1);
  plVar15 = param_1;
LAB_001792c8:
  if (*(long *)(lVar3 + 0x28) == local_70) {
    return plVar15;
  }
                    /* WARNING: Subroutine does not return */
  __stack_chk_fail();
}



// ========== FUN_001795b4 @ 001795b4 ==========

void FUN_001795b4(undefined8 param_1)

{
  ulong uVar1;
  undefined4 *puVar2;
  undefined4 *puVar3;
  size_t __n;
  void *pvVar4;
  undefined4 uVar5;
  undefined8 uVar6;
  undefined8 uVar7;
  void *__dest;
  ulong __n_00;
  undefined4 *puVar8;
  
  uVar6 = FUN_0016d578();
  uVar7 = FUN_0016d580(param_1);
  uVar5 = FUN_001787d8(0,uVar6,uVar7);
  pvVar4 = DAT_0020af90;
  if (DAT_0020af98 == DAT_0020afa0) {
    __n_00 = (long)DAT_0020af98 - (long)DAT_0020af90;
    uVar1 = ((long)__n_00 >> 2) + 1;
    if (uVar1 >> 0x3e != 0) {
                    /* WARNING: Subroutine does not return */
      std::__ndk1::__vector_base_common<true>::__throw_length_error();
    }
    if (uVar1 <= (ulong)((long)__n_00 >> 1)) {
      uVar1 = (long)__n_00 >> 1;
    }
    if (0x7ffffffffffffffb < __n_00) {
      uVar1 = 0x3fffffffffffffff;
    }
    if (uVar1 == 0) {
      __dest = (void *)0x0;
    }
    else {
      if (uVar1 >> 0x3e != 0) {
                    /* WARNING: Subroutine does not return */
        FUN_0016daf0("allocator<T>::allocate(size_t n) \'n\' exceeds maximum supported size");
      }
      __dest = operator_new(uVar1 << 2);
    }
    puVar2 = (undefined4 *)((long)__dest + ((long)__n_00 >> 2) * 4);
    puVar3 = (undefined4 *)((long)__dest + uVar1 * 4);
    puVar8 = puVar2 + 1;
    *puVar2 = uVar5;
    if (0 < (long)__n_00) {
      memcpy(__dest,pvVar4,__n_00);
    }
    DAT_0020af90 = __dest;
    DAT_0020af98 = puVar8;
    DAT_0020afa0 = puVar3;
    if (pvVar4 != (void *)0x0) {
      operator_delete(pvVar4);
    }
  }
  else {
    *DAT_0020af98 = uVar5;
    DAT_0020af98 = DAT_0020af98 + 1;
  }
  pvVar4 = DAT_0020af90;
  if (200 < (ulong)((long)DAT_0020af98 - (long)DAT_0020af90)) {
    __n = (long)DAT_0020af98 - ((long)DAT_0020af90 + 4);
    if (__n != 0) {
      memmove(DAT_0020af90,(void *)((long)DAT_0020af90 + 4),__n);
    }
    DAT_0020af98 = (undefined4 *)((long)pvVar4 + __n);
  }
  return;
}



// ========== FUN_00175c30 @ 00175c30 ==========

void FUN_00175c30(long *param_1,undefined8 param_2,undefined8 param_3,undefined8 param_4,
                 undefined8 param_5,undefined8 param_6,undefined8 param_7,undefined8 param_8)

{
  long lVar1;
  long lVar2;
  undefined1 auStack_a0 [8];
  undefined8 local_98;
  undefined8 uStack_90;
  undefined8 local_88;
  undefined8 uStack_80;
  undefined8 local_78;
  undefined1 *local_70;
  undefined1 **ppuStack_68;
  undefined1 *puStack_60;
  undefined8 uStack_58;
  
  ppuStack_68 = &local_70;
  puStack_60 = auStack_a0;
  lVar1 = tpidr_el0;
  lVar2 = *(long *)(lVar1 + 0x28);
  uStack_58 = 0xffffff80ffffffd8;
  local_98 = param_4;
  uStack_90 = param_5;
  local_88 = param_6;
  uStack_80 = param_7;
  local_78 = param_8;
  local_70 = (undefined1 *)register0x00000008;
  (**(code **)(*param_1 + 0x118))();
  if (*(long *)(lVar1 + 0x28) == lVar2) {
    return;
  }
                    /* WARNING: Subroutine does not return */
  __stack_chk_fail();
}



// ========== FUN_0016d580 @ 0016d580 ==========

undefined8 FUN_0016d580(long param_1)

{
  return *(undefined8 *)(param_1 + 0x30);
}



// ========== FUN_0016d578 @ 0016d578 ==========

undefined8 FUN_0016d578(long param_1)

{
  return *(undefined8 *)(param_1 + 0x20);
}


