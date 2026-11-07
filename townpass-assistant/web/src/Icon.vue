<script setup>
defineProps({ name: String })
</script>

<template>
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"
    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-if="name === 'mic'">
      <rect x="9" y="2.5" width="6" height="12" rx="3" />
      <path d="M5 11v1a7 7 0 0 0 14 0v-1M12 19v3" />
    </template>
    <template v-else-if="name === 'sun'">
      <circle cx="12" cy="12" r="5" />
      <path d="M12 1v3m0 16v3M1 12h3m16 0h3M4.3 4.3l2.1 2.1m11.2 11.2 2.1 2.1M19.7 4.3l-2.1 2.1M6.4 17.6l-2.1 2.1" />
    </template>
    <path v-else-if="name === 'wave'" stroke-width="2.8" d="M3 11v2m4-6v10m5-14v18m5-14v10m4-6v2" />
    <path v-else-if="name === 'back'" d="m15 5-7 7 7 7" />
    <template v-else-if="name === 'settings'">
      <path d="m10 3-.5 2.1-1.5.8-2-.6L4 8.8l1.6 1.5v1.8L4 13.6l2 3.5 2-.6 1.5.8.5 2.2h4l.5-2.2 1.5-.8 2 .6 2-3.5-1.6-1.5v-1.8L20 8.8l-2-3.5-2 .6-1.5-.8L14 3z" />
      <circle cx="12" cy="11.3" r="2.5" />
    </template>
    <path v-else-if="name === 'home'" fill="currentColor" stroke-width="1" d="m2 11 10-9 10 9h-3v10h-5v-6h-4v6H5V11z" />
    <path v-else-if="name === 'grid'" d="M4 9V4h5v5zm11 0V4h5v5zM4 20v-5h5v5zm11 0v-5h5v5zM12 4v2m0 12v2M4 12h2m12 0h2" />
    <template v-else-if="name === 'person'"><circle cx="12" cy="7" r="4" /><path d="M4 22v-1a8 8 0 0 1 16 0v1" /></template>
    <template v-else-if="name === 'greeting'">
      <circle cx="12" cy="9" r="4" /><path d="M5 22v-1a7 7 0 0 1 14 0v1M12 1v1M4 4l1 1m14 0 1-1" />
    </template>
    <template v-else-if="name === 'robot'">
      <rect x="5" y="7" width="14" height="12" rx="4" /><path d="M12 7V4m-2 11h4M3 11v4m18-4v4m-12-3h.1m5.9 0h.1" />
    </template>
    <path v-else-if="name === 'sound'" d="M3 9h4l5-4v14l-5-4H3zm13-1a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14" />
    <path v-else-if="name === 'external'" d="M13 4h7v7m0-7-9 9m-2-8H5a1 1 0 0 0-1 1v13a1 1 0 0 0 1 1h13a1 1 0 0 0 1-1v-4" />
    <path v-else-if="name === 'check'" d="m4 12 5 5L20 6" />
    <rect v-else-if="name === 'stop'" x="6" y="6" width="12" height="12" rx="1.5" fill="currentColor" stroke="none" />
  </svg>
</template>
