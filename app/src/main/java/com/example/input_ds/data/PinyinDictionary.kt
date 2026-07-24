package com.example.input_ds.data

/**
 * 完整拼音音节词典
 *
 * 包含汉语拼音方案中所有约 413 个合法音节（不含声调）。
 * 这些音节将用于构建 Trie 和拼音合法性校验。
 */
object PinyinDictionary {

    /** 所有合法拼音音节列表（小写，无调号） */
    val ALL_PINYIN: List<String> = listOf(
        // a 系列
        "a", "ai", "an", "ang", "ao",
        // b 系列
        "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian",
        "biao", "bie", "bin", "bing", "bo", "bu",
        // c 系列
        "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai",
        "chan", "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou",
        "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci",
        "cong", "cou", "cu", "cuan", "cui", "cun", "cuo",
        // d 系列
        "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di",
        "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui",
        "dun", "duo",
        // e 系列
        "e", "ei", "en", "eng", "er",
        // f 系列
        "fa", "fan", "fang", "fei", "fen", "feng", "fo", "fou", "fu",
        // g 系列
        "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong",
        "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
        // h 系列
        "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong",
        "hou", "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
        // j 系列
        "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu",
        "ju", "juan", "jue", "jun",
        // k 系列
        "ka", "kai", "kan", "kang", "kao", "ke", "kei", "ken", "keng", "kong",
        "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
        // l 系列
        "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia",
        "lian", "liang", "liao", "lie", "lin", "ling", "liu", "long", "lou", "lu",
        "luan", "lun", "luo", "lv", "lve",
        // m 系列
        "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi",
        "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
        // n 系列
        "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni",
        "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu",
        "nuan", "nuo", "nv", "nve",
        // o 系列
        "o", "ou",
        // p 系列
        "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian",
        "piao", "pie", "pin", "ping", "po", "pou", "pu",
        // q 系列
        "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu",
        "qu", "quan", "que", "qun",
        // r 系列
        "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru",
        "rua", "ruan", "rui", "run", "ruo",
        // s 系列
        "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai",
        "shan", "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou",
        "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si",
        "song", "sou", "su", "suan", "sui", "sun", "suo",
        // t 系列
        "ta", "tai", "tan", "tang", "tao", "te", "teng", "ti", "tian", "tiao",
        "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
        // w 系列
        "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
        // x 系列
        "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu",
        "xu", "xuan", "xue", "xun",
        // y 系列
        "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong",
        "you", "yu", "yuan", "yue", "yun",
        // z 系列
        "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha",
        "zhai", "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi",
        "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun",
        "zhuo", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo"
    )

    /** 拼音音节到频率的映射（频率用于排序，数值越大越常用） */
    val PINYIN_FREQUENCY: Map<String, Int> = mapOf(
        "de" to 100, "shi" to 99, "yi" to 98, "bu" to 97, "you" to 96,
        "zhi" to 95, "le" to 94, "ji" to 93, "wo" to 92, "ren" to 91,
        "zai" to 90, "ta" to 89, "you" to 88, "da" to 87, "lai" to 86,
        "shang" to 85, "guo" to 84, "zhong" to 83, "wei" to 82, "zi" to 81,
        "he" to 80, "fa" to 79, "sheng" to 78, "xue" to 77, "gong" to 76,
        "nian" to 75, "jiu" to 74, "hui" to 73, "neng" to 72, "xia" to 71,
        "da" to 70, "cheng" to 69, "duo" to 68, "tian" to 67, "jia" to 66,
        "kai" to 65, "dui" to 64, "xian" to 63, "yang" to 62, "li" to 61,
        "men" to 60, "fang" to 59, "shuo" to 58, "jian" to 57, "xin" to 56,
        "dong" to 55, "ming" to 54, "gao" to 53, "chang" to 52, "wen" to 51,
        "hao" to 50, "qian" to 49, "jin" to 48, "dao" to 47, "si" to 46,
        "nan" to 45, "wan" to 44, "hua" to 43, "er" to 42, "dian" to 41,
        "xiang" to 40, "qu" to 39, "mei" to 38, "qing" to 37, "jiao" to 36,
        "hou" to 35, "tong" to 34, "xi" to 33, "zheng" to 32, "ben" to 31,
        "gan" to 30, "bian" to 29, "mi" to 28, "qian" to 27, "shao" to 26,
        "ru" to 25, "bei" to 24, "yao" to 23, "jian" to 22, "hai" to 21,
        "ni" to 20, "ke" to 19, "ti" to 18, "dan" to 17, "chuan" to 16,
        "guang" to 15, "qie" to 14, "pian" to 13, "tou" to 12, "min" to 11
    )

    /**
     * 构建并返回一个填充了所有合法拼音音节的 PinyinTrie
     */
    fun buildTrie(): PinyinTrie {
        val trie = PinyinTrie()
        ALL_PINYIN.forEach { trie.insert(it) }
        return trie
    }
}
