package com.roadeye.app.camera

/**
 * Перевод кодов классов датасета "Traffic Signs Detection Europe"
 * (Radu Oprea, Roboflow) в русские фразы для озвучивания. Ключи должны
 * ТОЧНО совпадать со строками в labels.txt, который генерируется прямо
 * из data.yaml датасета (см. Colab-ноутбук обучения) — при несовпадении
 * используется fallback (сам код), поэтому опечатка не уронит
 * приложение, просто озвучится не очень красиво.
 */
object SignLabelsRu {

    private val map = mapOf(
        // Запрещающие (forb_*)
        "forb_ahead" to "Движение прямо запрещено",
        "forb_left" to "Поворот налево запрещён",
        "forb_overtake" to "Обгон запрещён",
        "forb_right" to "Поворот направо запрещён",
        "forb_speed_over_5" to "Ограничение скорости 5",
        "forb_speed_over_10" to "Ограничение скорости 10",
        "forb_speed_over_20" to "Ограничение скорости 20",
        "forb_speed_over_30" to "Ограничение скорости 30",
        "forb_speed_over_40" to "Ограничение скорости 40",
        "forb_speed_over_50" to "Ограничение скорости 50",
        "forb_speed_over_60" to "Ограничение скорости 60",
        "forb_speed_over_70" to "Ограничение скорости 70",
        "forb_speed_over_80" to "Ограничение скорости 80",
        "forb_speed_over_90" to "Ограничение скорости 90",
        "forb_speed_over_100" to "Ограничение скорости 100",
        "forb_speed_over_130" to "Ограничение скорости 130",
        "forb_stopping" to "Остановка запрещена",
        "forb_trucks" to "Движение грузовых запрещено",
        "forb_u_turn" to "Разворот запрещён",
        "forb_weight_over_3.5t" to "Движение массой более 3.5 тонн запрещено",
        "forb_weight_over_7.5t" to "Движение массой более 7.5 тонн запрещено",

        // Информационные (info_*)
        "info_bus_station" to "Остановка автобуса",
        "info_crosswalk" to "Пешеходный переход",
        "info_highway" to "Автомагистраль",
        "info_one_way_traffic" to "Одностороннее движение",
        "info_parking" to "Парковка",
        "info_taxi_parking" to "Стоянка такси",

        // Предписывающие (mand_*)
        "mand_bike_lane" to "Велосипедная дорожка",
        "mand_left" to "Движение только налево",
        "mand_left_right" to "Движение налево или направо",
        "mand_pass_left" to "Объезд препятствия слева",
        "mand_pass_left_right" to "Объезд препятствия слева или справа",
        "mand_pass_right" to "Объезд препятствия справа",
        "mand_right" to "Движение только направо",
        "mand_roundabout" to "Круговое движение",
        "mand_straigh_left" to "Движение прямо или налево",
        "mand_straight" to "Движение только прямо",
        "mand_straight_right" to "Движение прямо или направо",

        // Приоритет (prio_*)
        "prio_give_way" to "Уступите дорогу",
        "prio_priority_road" to "Главная дорога",
        "prio_stop" to "Стоп",

        // Предупреждающие (warn_*)
        "warn_children" to "Дети",
        "warn_construction" to "Дорожные работы",
        "warn_crosswalk" to "Пешеходный переход впереди",
        "warn_cyclists" to "Велосипедисты",
        "warn_domestic_animals" to "Внимание, домашние животные",
        "warn_other_dangers" to "Внимание, опасность",
        "warn_poor_road_surface" to "Неровная дорога",
        "warn_roundabout" to "Круговое движение впереди",
        "warn_slippery_road" to "Скользкая дорога",
        "warn_speed_bumper" to "Неровная дорога, лежачий полицейский",
        "warn_traffic_light" to "Светофорное регулирование",
        "warn_tram" to "Трамвайные пути",
        "warn_two_way_traffic" to "Двустороннее движение",
        "warn_wild_animals" to "Внимание, дикие животные"
    )

    /**
     * Возвращает русскую фразу для озвучивания. Если код неизвестен —
     * возвращает сам код как fallback (лучше, чем молчание/краш).
     */
    fun phraseFor(code: String): String = map[code] ?: code
}